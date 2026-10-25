package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.registry.Appliance;

import java.time.Instant;

//An appliance now: its cycle, the last valid reading of its plug and whether the last poll was answered.
//What is not known since the start of the pod is null
public record ApplianceStatus(Appliance appliance, CycleView cycle, Double watts, Instant sampledAt, Boolean reachable) {
}
