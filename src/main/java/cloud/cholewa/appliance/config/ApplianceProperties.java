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
 * of 90 days keeps the table at about 800 000 rows.<br>
 * {@code appliance.claim-window} - how long after its end a cycle can still be claimed with a tag (HAS-343). A
 * bare number is hours; at least an hour, so a bare number read as milliseconds can never shut every claim out,
 * at most two days.
 */
@Validated
@ConfigurationProperties(prefix = "appliance")
public record ApplianceProperties(
    @NotNull @DurationMin(days = 2) @DurationMax(days = 90) @DurationUnit(ChronoUnit.DAYS)
    @DefaultValue("14d") Duration sampleRetention,
    @NotNull @DurationMin(hours = 1) @DurationMax(days = 2) @DurationUnit(ChronoUnit.HOURS)
    @DefaultValue("12h") Duration claimWindow
) {
}
