package cloud.cholewa.appliance.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * {@code appliance.sample-retention} - how long the samples of the plugs are kept; older ones are deleted
 * every night (SampleRetention).<br>
 * A bare number is days: without the unit it would bind as milliseconds and the first night would delete
 * every sample - hence the lower bound of 2 days, the longest range of the samples endpoint. The upper bound
 * of 90 days keeps the table at about 800 000 rows.
 */
@Validated
@ConfigurationProperties(prefix = "appliance")
public record ApplianceProperties(
    @NotNull @DurationMin(days = 2) @DurationMax(days = 90) @DurationUnit(ChronoUnit.DAYS)
    @DefaultValue("14d") Duration sampleRetention
) {
}
