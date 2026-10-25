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
    //Not watched: 404; nothing finished waiting: 409
    public Mono<Void> acknowledge(final long id) {
        return Mono.defer(() -> {
            final TrackedAppliance appliance = tracked.get(id);
            if (appliance == null) {
                return Mono.error(ApplianceException.notFound(id));
            }
            final Long runId = appliance.runId();
            if (runId == null || appliance.detector().view().state() != ApplianceState.FINISHED) {
                return Mono.error(ApplianceException.nothingToAcknowledge(id));
            }
            final Instant now = Instant.now(clock);
            //a row gone from the database leaves nothing to remind either: the cycle is acknowledged in memory
            return runStore.acknowledge(runId, now)
                .map(ApplianceRunEntity::remindersSent)
                .defaultIfEmpty(0)
                .flatMap(remindersSent -> {
                    //the detector of a cycle the sampling pass replaced meanwhile stays as it is, with its row
                    if (appliance.detector().acknowledge().isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("{} ({}): acknowledged", appliance.appliance().name(), id);
                    appliance.clearRunId(runId);
                    return householdNotifier.acknowledged(appliance.appliance(), now, remindersSent);
                })
                .then();
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

    private Mono<Void> track(final Appliance appliance) {
        final TrackedAppliance known = tracked.get(appliance.id());
        if (known != null) {
            known.appliance(appliance);
            return Mono.empty();
        }
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
        appliance.polled(sample);
        if (!sample.valid()) {
            return Mono.empty();
        }
        final long deviceId = appliance.appliance().id();

        return store(deviceId, sample)
            //after the sample is stored and inside the subscription the pass is bounded by
            .then(Mono.fromCallable(() -> appliance.detector()
                .sample(sample.at(), sample.watts(), sample.totalWattMinutes(), appliance.appliance().thresholds())))
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
