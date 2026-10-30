package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
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
}
