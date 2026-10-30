package cloud.cholewa.appliance.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of appliance_tag (V5): a tag that was read, the counter of its latest accepted read. Written only through
//ApplianceTagRepository.advance - never saved, the UID is assigned
@Table("appliance_tag")
public record ApplianceTagEntity(
    @Id
    String uid,
    long deviceId,
    int lastCounter,
    Instant lastClaimAt
) {
}
