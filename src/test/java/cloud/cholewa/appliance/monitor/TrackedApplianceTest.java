package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.CycleDetector;
import org.junit.jupiter.api.Test;

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
}
