package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.cycle.RunStore;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.power.PowerReader;
import cloud.cholewa.appliance.power.PowerSample;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.registry.ApplianceRegistry;
import cloud.cholewa.appliance.reminder.HouseholdNotifier;
import cloud.cholewa.appliance.reminder.Reminders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The appliances the service watches and the cycle of each (HAS-278). The registry decides which there are
//(refresh, every 5 min): a new one starts from its latest run, one that is gone is forgotten - its rows
//stay. Every 30 s the plugs are read (sample): a valid sample is stored and given to the detector of its
//appliance, a transition is written to appliance_run. Every minute each finished cycle is checked for its
//messages (remind, HAS-279) - the finish is announced at the first check, at most a minute after the end:
//not in the sampling pass, which a slow broker must not stall. None signals an error; acknowledge answers the API
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplianceMonitor {

    //the plugs are read at once, the database is written one appliance after the other - its pool has 2
    private static final int READ_CONCURRENCY = 8;

    private final ApplianceRegistry applianceRegistry;
    private final RunStore runStore;
    private final PowerReader powerReader;
    private final ApplianceSampleRepository applianceSampleRepository;
    private final Reminders reminders;
    private final HouseholdNotifier householdNotifier;
    private final Clock clock;
    private final Map<Long, TrackedAppliance> tracked = new ConcurrentHashMap<>();

    public Mono<Void> refresh() {
        return applianceRegistry.read()
            .flatMapMany(appliances -> {
                tracked.keySet().removeIf(id -> appliances.stream().noneMatch(appliance -> appliance.id() == id));
                return Flux.fromIterable(appliances);
            })
            .concatMap(this::track)
            .then();
    }

    public Mono<Void> sample() {
        return Flux.fromIterable(List.copyOf(tracked.values()))
            .flatMap(appliance -> powerReader.read(appliance.appliance())
                .map(sample -> new Polled(appliance, sample)), READ_CONCURRENCY)
            .concatMap(this::take)
            .then();
    }

    //the reminders of every finished cycle nobody acknowledged, one appliance after the other
    public Mono<Void> remind() {
        return Flux.fromIterable(List.copyOf(tracked.values()))
            .filter(appliance -> appliance.runId() != null
                && appliance.detector().view().state() == ApplianceState.FINISHED)
            .concatMap(appliance -> reminders.check(appliance.appliance(), appliance.runId()))
            .then();
    }

    //the finished cycle of an appliance acknowledged: the row first, so that a failed write leaves the cycle
    //waiting (and reminded), then the detector, then the message - which never fails the acknowledgement.
    //Not watched: 404; nothing finished waiting: 409. The member (HAS-347) is a name of the registry already
    //checked by ApplianceService, or null when nobody was named. A cycle still finishing is acknowledged at once
    //(HAS-348, acknowledgeFinishing) - and one the sampling pass has just finished goes on as a finished one
    public Mono<Void> acknowledge(final long id, final String member) {
        return Mono.justOrEmpty(tracked.get(id))
            .switchIfEmpty(Mono.error(() -> ApplianceException.notFound(id)))
            .flatMap(appliance -> acknowledgeFinishing(appliance, member)
                .switchIfEmpty(Mono.defer(() -> acknowledgeFinished(appliance, member).thenReturn(true))))
            .then();
    }

    private Mono<Void> acknowledgeFinished(final TrackedAppliance appliance, final String member) {
        final long id = appliance.appliance().id();
        return Mono.fromSupplier(appliance::runId)
            .filter(runId -> appliance.detector().view().state() == ApplianceState.FINISHED)
            .switchIfEmpty(Mono.error(() -> ApplianceException.nothingToAcknowledge(id)))
            .flatMap(runId -> Mono.fromSupplier(() -> Instant.now(clock))
                //a row gone from the database leaves nothing to remind either: the cycle is acknowledged in memory
                .flatMap(now -> runStore.acknowledge(runId, now, member)
                    .map(ApplianceRunEntity::remindersSent)
                    .defaultIfEmpty(0)
                    //the detector of a cycle the sampling pass replaced meanwhile stays as it is, with its row
                    .flatMap(remindersSent -> Mono.justOrEmpty(appliance.detector().acknowledge())
                        .thenReturn(remindersSent))
                    .doOnNext(remindersSent -> {
                        log.info("{} ({}): acknowledged by {}", appliance.appliance().name(), id,
                            Objects.requireNonNullElse(member, "nobody named"));
                        appliance.clearRunId(runId);
                    })
                    .flatMap(remindersSent ->
                        householdNotifier.acknowledged(appliance.appliance(), now, remindersSent, member))))
            .then();
    }

    //HAS-348: emptied before the end was certain. The detector first - from FINISHING straight to IDLE, so the
    //cycle is never FINISHED and neither the reminder pass nor the sampling pass can announce it - then its end
    //and its acknowledgement in one write, then only "appliance acknowledged" (no reminder before it). A row
    //that cannot be written answers the error and the cycle is finishing again - the next sample ends it as an
    //ordinary one, announced and reminded. A cycle shorter than minRunMinutes is acknowledged too - a member saw
    //it. Empty when the detector is not finishing - the caller then takes the path of a finished one (409 for
    //anything else)
    private Mono<Boolean> acknowledgeFinishing(final TrackedAppliance appliance, final String member) {
        final long id = appliance.appliance().id();
        final Long runId = appliance.runId();
        final Instant now = Instant.now(clock);
        return Mono.defer(() -> Mono.justOrEmpty(appliance.detector().acknowledgeFinishing()))
            .flatMap(finished -> runStore.finishAcknowledged(id, runId, finished, now, member)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("the row of the cycle was not stored")))
                .doOnError(e -> appliance.detector().reopen(finished)))
            .flatMap(storedRunId -> {
                log.info("{} ({}): acknowledged while finishing by {}", appliance.appliance().name(), id,
                    Objects.requireNonNullElse(member, "nobody named"));
                appliance.clearRunId(runId);
                appliance.clearRunId(storedRunId);
                return householdNotifier.acknowledged(appliance.appliance(), now, 0, member);
            });
    }

    public List<ApplianceStatus> statuses() {
        return tracked.values().stream()
            .map(ApplianceMonitor::status)
            .sorted(Comparator.comparingLong(status -> status.appliance().id()))
            .toList();
    }

    public Optional<ApplianceStatus> status(final long id) {
        return Optional.ofNullable(tracked.get(id)).map(ApplianceMonitor::status);
    }

    //a known appliance takes what the registry says of it now; a new one starts from its latest run
    private Mono<Void> track(final Appliance appliance) {
        return Mono.justOrEmpty(tracked.get(appliance.id()))
            .doOnNext(known -> known.appliance(appliance))
            .map(known -> true)
            .switchIfEmpty(Mono.defer(() -> restore(appliance).thenReturn(true)))
            .then();
    }

    private Mono<Void> restore(final Appliance appliance) {
        return runStore.restore(appliance.id(), appliance.thresholds())
            .doOnNext(restored -> {
                tracked.put(appliance.id(), new TrackedAppliance(appliance, restored.detector(), restored.runId()));
                log.info("Watching {} ({}), {}", appliance.name(), appliance.id(), restored.detector().view().state().word());
            })
            .onErrorResume(e -> {
                log.warn("Could not restore the cycle of {} ({}), tried again with the next refresh: {}",
                    appliance.name(), appliance.id(), e.toString());
                return Mono.empty();
            })
            .then();
    }

    private Mono<Void> take(final Polled polled) {
        final TrackedAppliance appliance = polled.appliance();
        final PowerSample sample = polled.sample();
        final long deviceId = appliance.appliance().id();
        appliance.polled(sample);

        //an invalid sample is never stored nor given to the detector: it keeps the state
        return Mono.just(sample)
            .filter(PowerSample::valid)
            .flatMap(valid -> store(deviceId, valid)
                //after the sample is stored and inside the subscription the pass is bounded by
                .then(Mono.fromCallable(() -> appliance.detector()
                    .sample(valid.at(), valid.watts(), valid.totalWattMinutes(), appliance.appliance().thresholds()))))
            .flatMap(Mono::justOrEmpty)
            .flatMap(transition -> {
                log.info("{} ({}): {}", appliance.appliance().name(), deviceId, transition);
                return runStore.write(deviceId, appliance.runId(), transition)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .doOnNext(runId -> appliance.runId(runId.orElse(null)));
            })
            .then();
    }

    private Mono<Void> store(final long deviceId, final PowerSample sample) {
        return applianceSampleRepository.save(new ApplianceSampleEntity(null, deviceId, sample.at(),
                BigDecimal.valueOf(sample.watts()).setScale(2, RoundingMode.HALF_UP)))
            .then()
            .onErrorResume(e -> {
                log.warn("Could not store a sample of appliance {}: {}", deviceId, e.toString());
                return Mono.empty();
            });
    }

    private static ApplianceStatus status(final TrackedAppliance appliance) {
        final CycleView cycle = appliance.detector().view();
        final PowerSample poll = appliance.lastPoll();
        final PowerSample reading = appliance.lastReading();
        return new ApplianceStatus(
            appliance.appliance(),
            cycle,
            reading == null ? null : reading.watts(),
            reading == null ? null : reading.at(),
            poll == null ? null : poll.answered()
        );
    }

    private record Polled(TrackedAppliance appliance, PowerSample sample) {
    }
}
