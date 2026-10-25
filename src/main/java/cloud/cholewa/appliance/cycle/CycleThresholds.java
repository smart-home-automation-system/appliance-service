package cloud.cholewa.appliance.cycle;

import cloud.cholewa.home.model.ApplianceSettings;

import java.time.Duration;

//The part of the ApplianceSettings of the registry the detector reads; the reminders are HAS-279's
public record CycleThresholds(
    int runningAboveWatts,
    int idleBelowWatts,
    Duration minRun,
    Duration finishedAfter
) {

    public static CycleThresholds of(final ApplianceSettings settings) {
        return new CycleThresholds(
            settings.getRunningAboveWatts(),
            settings.getIdleBelowWatts(),
            Duration.ofMinutes(settings.getMinRunMinutes()),
            Duration.ofMinutes(settings.getFinishedAfterMinutes())
        );
    }
}
