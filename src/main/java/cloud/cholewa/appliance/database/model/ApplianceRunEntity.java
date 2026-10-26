package cloud.cholewa.appliance.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of appliance_run (V1, V3, V4): one cycle of an appliance and its messages; what is not known yet is null
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
    String acknowledgedBy
) {
}
