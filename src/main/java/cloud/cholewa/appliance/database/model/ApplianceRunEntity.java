package cloud.cholewa.appliance.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of appliance_run (V1): one cycle of an appliance; what is not known yet is null
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
    int remindersSent
) {
}
