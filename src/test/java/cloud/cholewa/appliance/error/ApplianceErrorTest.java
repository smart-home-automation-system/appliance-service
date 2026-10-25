package cloud.cholewa.appliance.error;

import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//wire contract of the dashboard: the codes of the errors and the words of the states
class ApplianceErrorTest {

    @Test
    void should_keep_the_codes_of_the_errors() {
        assertThat(Arrays.stream(ApplianceError.values()).map(ErrorId::codeOf)).containsExactly("NOT_FOUND_APPLIANCE");
    }

    @Test
    void should_keep_the_words_of_the_states() {
        assertThat(Arrays.stream(ApplianceState.values()).map(ApplianceState::word))
            .containsExactly("idle", "running", "finishing", "finished");
    }
}
