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

    @EnableConfigurationProperties(ApplianceProperties.class)
    static class Enabled {
    }
}
