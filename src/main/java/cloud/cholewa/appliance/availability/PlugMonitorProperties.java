package cloud.cholewa.appliance.availability;

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
 * The availability monitor of the plugs (HAS-280), {@code appliance.monitor.*}; a bare number is minutes.
 *
 * @param offlineAfter  how long a plug gives no reading - no answer, or an answer without one - before it is out:
 *                      the plugs are read every 30 s, so at least four failed reads (2 min); at most an hour,
 *                      a cycle may end unseen meanwhile
 * @param reminderEvery how often a plug still out is named again
 */
@Validated
@ConfigurationProperties(prefix = "appliance.monitor")
public record PlugMonitorProperties(
    @NotNull @DurationMin(minutes = 2) @DurationMax(hours = 1) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("PT5M") Duration offlineAfter,
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("PT1H") Duration reminderEvery
) {
}
