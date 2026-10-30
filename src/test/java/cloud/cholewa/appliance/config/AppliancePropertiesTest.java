package cloud.cholewa.appliance.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AppliancePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Enabled.class);

    @Test
    void should_keep_the_samples_for_fourteen_days_by_default() {
        runner.run(context -> assertThat(context.getBean(ApplianceProperties.class).sampleRetention())
            .isEqualTo(Duration.ofDays(14)));
    }

    //without the unit a bare number would bind as milliseconds, and the first night would delete everything
    @Test
    void should_read_a_bare_number_as_days() {
        runner.withPropertyValues("appliance.sample-retention=30")
            .run(context -> assertThat(context.getBean(ApplianceProperties.class).sampleRetention())
                .isEqualTo(Duration.ofDays(30)));
    }

    @Test
    void should_refuse_a_retention_shorter_than_the_range_of_the_samples_endpoint() {
        runner.withPropertyValues("appliance.sample-retention=1d").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_refuse_a_retention_longer_than_ninety_days() {
        runner.withPropertyValues("appliance.sample-retention=91d").run(context -> assertThat(context).hasFailed());
    }

    //HAS-343: a cycle can be claimed with a tag for twelve hours after its end
    @Test
    void should_keep_the_claim_window_at_twelve_hours_by_default() {
        runner.run(context -> assertThat(context.getBean(ApplianceProperties.class).claimWindow())
            .isEqualTo(Duration.ofHours(12)));
    }

    //without the unit a bare number would bind as milliseconds and no claim would ever find its cycle
    @Test
    void should_read_a_bare_number_of_the_claim_window_as_hours() {
        runner.withPropertyValues("appliance.claim-window=6")
            .run(context -> assertThat(context.getBean(ApplianceProperties.class).claimWindow())
                .isEqualTo(Duration.ofHours(6)));
    }

    @Test
    void should_refuse_a_claim_window_shorter_than_an_hour() {
        runner.withPropertyValues("appliance.claim-window=59m").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_refuse_a_claim_window_longer_than_two_days() {
        runner.withPropertyValues("appliance.claim-window=49h").run(context -> assertThat(context).hasFailed());
    }

    //HAS-344: a second member within ten minutes shares the cycle
    @Test
    void should_keep_the_attribution_window_at_ten_minutes_by_default() {
        runner.run(context -> assertThat(context.getBean(ApplianceProperties.class).attributionWindow())
            .isEqualTo(Duration.ofMinutes(10)));
    }

    //without the unit a bare number would bind as milliseconds and no second member could ever share a cycle
    @Test
    void should_read_a_bare_number_of_the_attribution_window_as_minutes() {
        runner.withPropertyValues("appliance.attribution-window=15")
            .run(context -> assertThat(context.getBean(ApplianceProperties.class).attributionWindow())
                .isEqualTo(Duration.ofMinutes(15)));
    }

    @Test
    void should_refuse_an_attribution_window_out_of_its_bounds() {
        runner.withPropertyValues("appliance.attribution-window=59s").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("appliance.attribution-window=61m").run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(ApplianceProperties.class)
    static class Enabled {
    }
}
