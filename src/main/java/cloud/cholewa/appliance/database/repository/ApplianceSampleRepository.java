package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ApplianceSampleRepository extends R2dbcRepository<ApplianceSampleEntity, Long> {

    //the samples in [from, to), the oldest first - the chart of the dashboard
    Flux<ApplianceSampleEntity> findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(
        long deviceId, Instant from, Instant to);

    //the retention: every sample taken before "before"
    @Modifying
    @Query("DELETE FROM appliance_sample WHERE at < :before")
    Mono<Long> deleteOlderThan(Instant before);
}
