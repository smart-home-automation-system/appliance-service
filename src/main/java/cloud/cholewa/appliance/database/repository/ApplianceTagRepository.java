package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceTagEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ApplianceTagRepository extends R2dbcRepository<ApplianceTagEntity, String> {

    //the counter of a verified read taken, in one statement: inserted for a tag read for the first time, moved on
    //only when it is above the stored one. 1 when taken, 0 for a counter not above the stored one - a replay. One
    //statement, so two posts of the same read at once cannot both be taken (PostgreSQL only - ON CONFLICT)
    @Modifying
    @Query("""
        INSERT INTO appliance_tag (uid, device_id, last_counter, last_claim_at)
        VALUES (:uid, :deviceId, :counter, :at)
        ON CONFLICT (uid) DO UPDATE
            SET device_id = EXCLUDED.device_id, last_counter = EXCLUDED.last_counter, last_claim_at = EXCLUDED.last_claim_at
            WHERE appliance_tag.last_counter < EXCLUDED.last_counter""")
    Mono<Integer> advance(String uid, long deviceId, int counter, Instant at);
}
