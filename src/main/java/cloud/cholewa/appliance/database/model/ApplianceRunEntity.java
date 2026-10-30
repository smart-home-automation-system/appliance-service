package cloud.cholewa.appliance.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

//A row of appliance_run (V1, V3, V4, V6): one cycle of an appliance and its messages; what is not known yet is null
@Builder(toBuilder = true)
@Table("appliance_run")
public record ApplianceRunEntity(
    @Id
    Long id,
    long deviceId,
    Instant startedAt,
    Instant finishedAt,
    Long energyWh,
    Integer maxWatts,
    Instant acknowledgedAt,
    int remindersSent,
    Instant notifiedAt,
    Instant lastMessageAt,
    //who acknowledged it in the application (HAS-347); null when nobody was named
    String acknowledgedBy,
    //HAS-344 (V6): who was at home when it finished and when it was completed - null not known, empty nobody;
    //when and how it was completed first (nfc, app, mute) and whose chore it is (single, shared, unattributed)
    List<String> presentAtFinish,
    Instant completedAt,
    String completionSource,
    List<String> attributedTo,
    String attribution,
    List<String> presentAtCompletion
) {
}
