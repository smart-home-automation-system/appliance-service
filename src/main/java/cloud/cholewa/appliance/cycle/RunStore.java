package cloud.cholewa.appliance.cycle;

import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.temporal.ChronoUnit;

//The cycles in appliance_run: the state an appliance starts from after a restart of the pod, and what a
//transition writes. A write that fails is a WARN and changes nothing in memory - the cycle goes on; the
//next transition writes what it knows (a finish without the row of its start inserts the whole row), so
//one failed write loses at most the row of a cycle that is then discarded
@Slf4j
@Component
@RequiredArgsConstructor
public class RunStore {

    private final ApplianceRunRepository applianceRunRepository;

    //the detector and the row of its cycle, from the latest run of the appliance: a run without an end
    //goes on running, a finished one nobody acknowledged waits again - since its end plus finishedAfter,
    //the moment it was reached as far as the row can tell
    public Mono<Restored> restore(final long deviceId, final CycleThresholds thresholds) {
        return applianceRunRepository.findFirstByDeviceIdOrderByStartedAtDesc(deviceId)
            .map(run -> restored(run, thresholds))
            .defaultIfEmpty(new Restored(CycleDetector.idle(), null));
    }

    static Restored restored(final ApplianceRunEntity run, final CycleThresholds thresholds) {
        if (run.finishedAt() == null) {
            return new Restored(CycleDetector.running(run.startedAt()), run.id());
        }
        if (run.acknowledgedAt() == null) {
            return new Restored(CycleDetector.finished(run.startedAt(), run.finishedAt(),
                run.finishedAt().plus(thresholds.finishedAfter())), run.id());
        }
        return new Restored(CycleDetector.idle(), null);
    }

    //the id of the row of the cycle after the transition - null when there is none
    public Mono<Long> write(final long deviceId, final Long runId, final Transition transition) {
        return switch (transition) {
            case Transition.Started started -> applianceRunRepository.save(ApplianceRunEntity.builder()
                    .deviceId(deviceId)
                    .startedAt(started.startedAt())
                    .build())
                .map(ApplianceRunEntity::id)
                .onErrorResume(e -> failed("the start of a cycle", deviceId, e, null));
            case Transition.Finished finished -> finish(deviceId, runId, finished);
            case Transition.Discarded discarded -> runId == null
                ? Mono.empty()
                : applianceRunRepository.deleteById(runId)
                    .then(Mono.<Long>empty())
                    .onErrorResume(e -> failed("the removal of a run below the minimum", deviceId, e, null));
            case Transition.Acknowledged acknowledged -> Mono.empty();
        };
    }

    private Mono<Long> finish(final long deviceId, final Long runId, final Transition.Finished finished) {
        final Mono<ApplianceRunEntity> row = runId == null
            ? Mono.empty()
            : applianceRunRepository.findById(runId).filter(run -> sameStart(run, finished));

        return row
            .defaultIfEmpty(ApplianceRunEntity.builder().deviceId(deviceId).startedAt(finished.startedAt()).build())
            .map(run -> run.toBuilder()
                .finishedAt(finished.finishedAt())
                .energyWh(finished.energyWh())
                .maxWatts(finished.maxWatts())
                .build())
            .flatMap(applianceRunRepository::save)
            .map(ApplianceRunEntity::id)
            .onErrorResume(e -> failed("the end of a cycle", deviceId, e, runId));
    }

    //the row of this cycle and not a stale one: timestamptz keeps microseconds, so a start with a finer fraction
    //in memory is compared at the precision the database stores
    private static boolean sameStart(final ApplianceRunEntity run, final Transition.Finished finished) {
        return run.startedAt().truncatedTo(ChronoUnit.MICROS).equals(finished.startedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static Mono<Long> failed(final String what, final long deviceId, final Throwable e, final Long runId) {
        log.warn("Could not store {} of appliance {}: {}", what, deviceId, e.toString());
        return Mono.justOrEmpty(runId);
    }

    public record Restored(CycleDetector detector, Long runId) {
    }
}
