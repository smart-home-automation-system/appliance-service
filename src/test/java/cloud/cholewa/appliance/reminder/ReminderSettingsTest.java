package cloud.cholewa.appliance.reminder;

import cloud.cholewa.home.model.ApplianceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderSettingsTest {

    @Test
    void should_read_the_settings_of_the_registry() {
        final ApplianceSettings settings = ApplianceSettings.builder()
            .runningAboveWatts(10).idleBelowWatts(5).minRunMinutes(30).finishedAfterMinutes(10)
            .reminderEveryMinutes(30).maxReminders(4).quietFrom("23:00").quietTo("07:00")
            .build();

        assertThat(ReminderSettings.of(settings))
            .isEqualTo(new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0)));
    }

    //quietFrom included, quietTo excluded, across midnight
    @ParameterizedTest
    @CsvSource({
        "22:59, false",
        "23:00, true",
        "02:30, true",
        "06:59, true",
        "07:00, false",
        "12:00, false"
    })
    void should_tell_the_quiet_hours_across_midnight(final LocalTime time, final boolean quiet) {
        assertThat(new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0)).isQuiet(time))
            .isEqualTo(quiet);
    }

    @ParameterizedTest
    @CsvSource({
        "12:59, false",
        "13:00, true",
        "14:59, true",
        "15:00, false"
    })
    void should_tell_quiet_hours_within_one_day(final LocalTime time, final boolean quiet) {
        assertThat(new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(13, 0), LocalTime.of(15, 0)).isQuiet(time))
            .isEqualTo(quiet);
    }

    @Test
    void should_have_no_quiet_hours_when_they_start_and_end_at_once() {
        assertThat(new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(7, 0), LocalTime.of(7, 0))
            .isQuiet(LocalTime.of(7, 0))).isFalse();
    }
}
