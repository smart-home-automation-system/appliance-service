package cloud.cholewa.appliance.registry;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.home.model.ApplianceSettings;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApplianceMapperTest {

    private static final ApplianceSettings SETTINGS = ApplianceSettings.builder()
        .runningAboveWatts(10).idleBelowWatts(3).minRunMinutes(10).finishedAfterMinutes(5)
        .reminderEveryMinutes(30).maxReminders(3).quietFrom("22:00").quietTo("07:00")
        .build();

    @Test
    void should_watch_a_gen1_plug_with_its_settings() {
        final SmartDevice device = device(ShellyBinding.builder().address("192.0.2.51").generation(1).build(), SETTINGS);

        assertThat(ApplianceMapper.map(device)).isEqualTo(new ApplianceMapper.Watched(new Appliance(
            51, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1,
            new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5)),
            new ReminderSettings(Duration.ofMinutes(30), 3, LocalTime.of(22, 0), LocalTime.of(7, 0)))));
    }

    @Test
    void should_read_the_meter_of_the_channel_of_the_binding() {
        final SmartDevice device = device(ShellyBinding.builder().address("192.0.2.51").generation(1).channel(1).build(),
            SETTINGS);

        assertThat(ApplianceMapper.map(device)).isInstanceOfSatisfying(ApplianceMapper.Watched.class,
            watched -> assertThat(watched.appliance().channel()).isEqualTo(1));
    }

    @Test
    void should_skip_a_gen2_plug_it_has_no_reader_for() {
        final SmartDevice device = device(ShellyBinding.builder().address("192.0.2.51").generation(3).build(), SETTINGS);

        assertThat(ApplianceMapper.map(device)).isEqualTo(new ApplianceMapper.Skipped("a Gen2+ plug is not read yet"));
    }

    @Test
    void should_skip_a_device_without_its_binding_or_its_settings() {
        assertThat(ApplianceMapper.map(device(null, SETTINGS))).isEqualTo(new ApplianceMapper.Skipped("no Shelly binding"));
        assertThat(ApplianceMapper.map(device(ShellyBinding.builder().address("192.0.2.51").generation(1).build(), null)))
            .isEqualTo(new ApplianceMapper.Skipped("no appliance settings"));
    }

    private static SmartDevice device(final ShellyBinding shelly, final ApplianceSettings settings) {
        return SmartDevice.builder()
            .id(51L).name("Dishwasher").room(RoomName.KITCHEN).kind(DeviceKind.APPLIANCE).enabled(true)
            .vendor(SmartVendor.SHELLY).shelly(shelly).appliance(settings)
            .build();
    }
}
