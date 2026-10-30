package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ApplianceRunRepository extends R2dbcRepository<ApplianceRunEntity, Long> {

    //the cycle an appliance starts from after a restart of the pod: the latest one it began
    Mono<ApplianceRunEntity> findFirstByDeviceIdOrderByStartedAtDesc(long deviceId);

    //the cycle a tag claim attaches to (HAS-343): the latest one that has finished
    Mono<ApplianceRunEntity> findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(long deviceId);

    //the cycles that began in [from, to), the oldest first
    Flux<ApplianceRunEntity> findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(
        long deviceId, Instant from, Instant to);

    //HAS-344: the attribution writes its own columns only - a save of the whole row would race the reminder pass, which
    //saves the row too (a lost notified_at announces a cycle twice). Who was at home when it finished, once
    @Modifying
    @Query("UPDATE appliance_run SET present_at_finish = :present WHERE id = :id AND present_at_finish IS NULL")
    Mono<Integer> presentAtFinish(long id, String[] present);

    //the attribution, applied only to the attribution it was computed from (null for a cycle not completed yet), so
    //two confirmations at once never undo each other - the later one changes no row. The presence at the completion
    //is kept once it is known: present is null when it is not, or when the cycle was completed before
    @Modifying
    @Query("""
        UPDATE appliance_run
        SET completed_at = :completedAt, completion_source = :source, attributed_to = :attributedTo,
            attribution = :attribution, present_at_completion = COALESCE(present_at_completion, :present)
        WHERE id = :id AND attribution IS NOT DISTINCT FROM :previous""")
    Mono<Integer> complete(long id, Instant completedAt, String source, String[] attributedTo, String attribution,
                           String[] present, String previous);
}
