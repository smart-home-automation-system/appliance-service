package cloud.cholewa.appliance.attribution;

import cloud.cholewa.appliance.attribution.AttributionRule.Attribution;
import cloud.cholewa.appliance.attribution.AttributionRule.Completion;
import cloud.cholewa.appliance.attribution.AttributionRule.Confirmed;
import cloud.cholewa.appliance.attribution.AttributionRule.Event;
import cloud.cholewa.appliance.attribution.AttributionRule.Muted;
import cloud.cholewa.appliance.attribution.AttributionRule.Source;
import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.presence.PresenceClient;
import cloud.cholewa.appliance.presence.PresenceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

//The attribution of the cycles in appliance_run (HAS-344): the presence when a cycle finishes, and what a
//confirmation or a mute makes of it (AttributionRule) with the presence at its completion. Its own columns only,
//each write conditional (ApplianceRunRepository). Nothing here fails its caller: a cycle, a claim or an
//acknowledgement goes on whatever happens to its attribution - a failure is a WARN (the claims stay in
//appliance_claim, from which the attribution can be told again)
@Slf4j
@Component
@RequiredArgsConstructor
public class RunAttribution {

    private final ApplianceRunRepository applianceRunRepository;
    private final PresenceClient presenceClient;
    private final ApplianceProperties applianceProperties;
    private final Clock clock;

    //the cycle reached FINISHED: who is at home now - written once; a snapshot not known writes nothing
    public Mono<Void> finished(final long runId) {
        return presenceClient.snapshot()
            .flatMap(snapshot -> Mono.justOrEmpty(snapshot.present()))
            .flatMap(present -> applianceRunRepository.presentAtFinish(runId, present.toArray(String[]::new)))
            .then()
            .onErrorResume(e -> failed("the presence at the finish", runId, e));
    }

    //a member confirmed the cycle - with the tag (nfc) or "Emptied" in the application (app)
    public Mono<Void> confirmed(final long runId, final String member, final Source source) {
        return Mono.defer(() -> apply(runId, new Confirmed(member, source, Instant.now(clock))));
    }

    //acknowledged without a member
    public Mono<Void> muted(final long runId) {
        return Mono.defer(() -> apply(runId, new Muted(Instant.now(clock))));
    }

    private Mono<Void> apply(final long runId, final Event event) {
        return applianceRunRepository.findById(runId)
            //completed in a way this version cannot read: left as it is, never completed anew
            .filter(run -> run.completedAt() == null || completion(run) != null)
            .flatMap(run -> Mono.justOrEmpty(AttributionRule.apply(completion(run), event, applianceProperties.attributionWindow()))
                .flatMap(next -> presenceAtCompletion(run)
                    .flatMap(snapshot -> applianceRunRepository.complete(runId, next.completedAt(), next.source().word(),
                        next.attributedTo().toArray(String[]::new), next.attribution().word(), names(snapshot),
                        run.attribution()))
                    .doOnNext(updated -> told(run, next, updated))))
            .then()
            .onErrorResume(e -> failed("the attribution", runId, e));
    }

    //the presence at the completion is taken when the cycle is completed first, never again
    private Mono<PresenceSnapshot> presenceAtCompletion(final ApplianceRunEntity run) {
        return run.completedAt() == null ? presenceClient.snapshot() : Mono.just(PresenceSnapshot.unknown());
    }

    private static String[] names(final PresenceSnapshot snapshot) {
        return snapshot.present() == null ? null : snapshot.present().toArray(String[]::new);
    }

    private static void told(final ApplianceRunEntity run, final Completion next, final int updated) {
        if (updated == 0) {
            log.warn("Cycle {}: its attribution changed meanwhile, {} {} not applied", run.id(), next.attribution().word(),
                next.attributedTo());
        } else {
            log.info("Cycle {} of appliance {}: {} {}", run.id(), run.deviceId(), next.attribution().word(),
                next.attributedTo());
        }
    }

    //what the row holds, or null for a cycle not completed yet - and for one completed in a way this version cannot read
    static Completion completion(final ApplianceRunEntity run) {
        if (run.completedAt() == null) {
            return null;
        }
        return Source.of(run.completionSource())
            .flatMap(source -> Attribution.of(run.attribution())
                .map(attribution -> new Completion(run.completedAt(), source,
                    run.attributedTo() == null ? List.of() : run.attributedTo(), attribution)))
            .orElse(null);
    }

    private static Mono<Void> failed(final String what, final long runId, final Throwable e) {
        log.warn("Could not store {} of cycle {}: {}", what, runId, e.toString());
        return Mono.empty();
    }
}
