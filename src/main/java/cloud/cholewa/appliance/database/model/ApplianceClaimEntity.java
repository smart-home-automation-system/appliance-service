package cloud.cholewa.appliance.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of appliance_claim (V5): a member claimed a finished cycle - with a tag read (source nfc, its counter)
@Builder
@Table("appliance_claim")
public record ApplianceClaimEntity(
    @Id
    Long id,
    long runId,
    String member,
    String source,
    Instant claimedAt,
    Integer counter
) {

    public static final String SOURCE_NFC = "nfc";
    //"Emptied" in the application with a member (HAS-344, owner 2026-10-30: equal to a tag read)
    public static final String SOURCE_APP = "app";
}
