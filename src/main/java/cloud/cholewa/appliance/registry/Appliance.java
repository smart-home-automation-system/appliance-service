package cloud.cholewa.appliance.registry;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.home.model.RoomName;

//An appliance as the service reads it from the registry: the plug it runs on (its address, the meter of
//its channel, its generation) and the thresholds of its cycle
public record Appliance(
    long id,
    String name,
    RoomName room,
    String address,
    int channel,
    int generation,
    CycleThresholds thresholds
) {
}
