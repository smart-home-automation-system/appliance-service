package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceClaimEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

public interface ApplianceClaimRepository extends R2dbcRepository<ApplianceClaimEntity, Long> {

    //the claims of one cycle, the first first
    Flux<ApplianceClaimEntity> findByRunIdOrderByClaimedAt(long runId);
}
