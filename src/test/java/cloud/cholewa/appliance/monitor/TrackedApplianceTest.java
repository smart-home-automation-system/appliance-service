package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.CycleDetector;
import cloud.cholewa.appliance.power.PowerSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedApplianceTest {

    //ids above 127 are not cached by Long.valueOf: the row is forgotten by its value, not by the reference
    @Test
    void should_forget_the_acknowledged_row_by_its_value() {
        final TrackedAppliance appliance = new TrackedAppliance(null, CycleDetector.idle(), Long.valueOf(1000));

        appliance.clearRunId(Long.valueOf(1000));

        assertThat(appliance.runId()).isNull();
    }

    //the sampling pass stored the row of a new cycle meanwhile: it stays
    @Test
    void should_keep_the_row_of_a_new_cycle() {
        final TrackedAppliance appliance = new TrackedAppliance(null, CycleDetector.idle(), 1001L);

        appliance.clearRunId(1000L);

        assertThat(appliance.runId()).isEqualTo(1001L);
    }

    //HAS-280: since the first poll without a reading - no answer and an answer without a reading alike - until
    //the next reading
    @Test
    void should_keep_since_when_a_plug_gives_no_reading() {
        final Instant t0 = Instant.parse("2026-10-26T18:00:00Z");
        final TrackedAppliance appliance = new TrackedAppliance(null, CycleDetector.idle(), null);

        appliance.polled(PowerSample.reading(t0, 2.0, null));
        assertThat(appliance.failingSince()).isNull();

        appliance.polled(PowerSample.unanswered(t0.plusSeconds(30)));
        appliance.polled(PowerSample.invalid(t0.plusSeconds(60)));
        assertThat(appliance.failingSince()).isEqualTo(t0.plusSeconds(30));

        appliance.polled(PowerSample.reading(t0.plusSeconds(90), 2.0, null));
        assertThat(appliance.failingSince()).isNull();
    }
}
