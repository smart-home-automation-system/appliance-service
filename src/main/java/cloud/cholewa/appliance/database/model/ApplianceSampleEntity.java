package cloud.cholewa.appliance.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

//A row of appliance_sample (V2): a valid sample of the draw of a plug
@Table("appliance_sample")
public record ApplianceSampleEntity(
    @Id
    Long id,
    long deviceId,
    Instant at,
    BigDecimal watts
) {
}
