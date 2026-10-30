package cloud.cholewa.appliance.nfc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NfcPropertiesTest {

    private static final String KEY = "00112233445566778899aabbccddeeff";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Enabled.class);

    @Test
    void should_read_the_key_and_the_tags_with_their_uids_in_upper_case() {
        runner.withPropertyValues("appliance.nfc.key=" + KEY, "appliance.nfc.tags.04a1b2c3d4e5f6=31")
            .run(context -> {
                final NfcProperties properties = context.getBean(NfcProperties.class);
                assertThat(properties.keyBytes()).hasSize(16);
                assertThat(properties.tags()).isEqualTo(Map.of("04A1B2C3D4E5F6", 31L));
            });
    }

    @Test
    void should_start_without_tags() {
        runner.withPropertyValues("appliance.nfc.key=" + KEY)
            .run(context -> assertThat(context.getBean(NfcProperties.class).tags()).isEmpty());
    }

    //the start fails and the message does not carry the value - it could be most of a real key
    @Test
    void should_refuse_a_key_that_is_not_32_hex_characters_without_naming_it() {
        runner.withPropertyValues("appliance.nfc.key=00112233445566778899aabbccddeeXX")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessage("appliance.nfc.key must be 32 hex characters (AES-128)"));
    }

    @Test
    void should_refuse_a_start_without_a_key() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_refuse_a_uid_that_is_not_14_hex_characters() {
        runner.withPropertyValues("appliance.nfc.key=" + KEY, "appliance.nfc.tags.04a1b2=31")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_leave_the_key_out_of_its_text() {
        assertThat(new NfcProperties(KEY, Map.of()).toString()).doesNotContain(KEY).contains("hidden");
    }

    @EnableConfigurationProperties(NfcProperties.class)
    static class Enabled {
    }
}
