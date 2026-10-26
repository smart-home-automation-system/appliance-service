package cloud.cholewa.appliance.availability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//appliance.monitor.* (HAS-280): a bare number is minutes, bounded
class PlugMonitorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Enabled.class);

    @Test
    void should_take_a_plug_as_out_after_five_minutes_and_remind_every_hour_by_default() {
        runner.run(context -> {
            assertThat(context.getBean(PlugMonitorProperties.class).offlineAfter()).isEqualTo(Duration.ofMinutes(5));
            assertThat(context.getBean(PlugMonitorProperties.class).reminderEvery()).isEqualTo(Duration.ofHours(1));
        });
    }

    //without the unit a bare number would bind as milliseconds: every plug out at its first missed read
    @Test
    void should_read_a_bare_number_as_minutes() {
        runner.withPropertyValues("appliance.monitor.offline-after=7", "appliance.monitor.reminder-every=90")
            .run(context -> {
                assertThat(context.getBean(PlugMonitorProperties.class).offlineAfter()).isEqualTo(Duration.ofMinutes(7));
                assertThat(context.getBean(PlugMonitorProperties.class).reminderEvery()).isEqualTo(Duration.ofMinutes(90));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "appliance.monitor.offline-after=1",
        "appliance.monitor.offline-after=61",
        "appliance.monitor.reminder-every=9",
        "appliance.monitor.reminder-every=PT24H1M"
    })
    void should_refuse_a_bound_out_of_its_range(final String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(PlugMonitorProperties.class)
    static class Enabled {
    }
}
