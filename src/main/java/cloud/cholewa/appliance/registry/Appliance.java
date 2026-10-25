package cloud.cholewa.appliance.registry;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.home.model.RoomName;

//An appliance as the service reads it from the registry: the plug it runs on (its address, the meter of
//its channel, its generation), the thresholds of its cycle and how the household is reminded (HAS-279)
public record Appliance(
    long id,
    String name,
    RoomName room,
    String address,
    int channel,
    int generation,
    CycleThresholds thresholds,
    ReminderSettings reminders
) {
}
