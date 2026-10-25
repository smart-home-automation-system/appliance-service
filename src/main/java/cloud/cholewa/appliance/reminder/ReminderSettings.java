package cloud.cholewa.appliance.reminder;

import cloud.cholewa.home.model.ApplianceSettings;

import java.time.Duration;
import java.time.LocalTime;

//The part of the ApplianceSettings of the registry the reminders read (HAS-279): how often, how many after the
//first message, and the quiet hours of the house - no reminder goes in them, one due then waits for their end.
//The message that the cycle has finished goes at once, quiet hours or not (owner, 2026-10-25)
public record ReminderSettings(Duration every, int max, LocalTime quietFrom, LocalTime quietTo) {

    public static ReminderSettings of(final ApplianceSettings settings) {
        return new ReminderSettings(
            Duration.ofMinutes(settings.getReminderEveryMinutes()),
            settings.getMaxReminders(),
            LocalTime.parse(settings.getQuietFrom()),
            LocalTime.parse(settings.getQuietTo())
        );
    }

    //quiet from quietFrom (included) to quietTo (excluded); a span that crosses midnight (23:00-07:00) is the
    //usual one; the same time twice is no quiet hours at all
    public boolean isQuiet(final LocalTime time) {
        if (quietFrom.equals(quietTo)) {
            return false;
        }
        if (quietFrom.isBefore(quietTo)) {
            return !time.isBefore(quietFrom) && time.isBefore(quietTo);
        }
        return !time.isBefore(quietFrom) || time.isBefore(quietTo);
    }
}
