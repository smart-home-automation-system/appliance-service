package cloud.cholewa.appliance.registry;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//An appliance of the registry the service can watch, or why not. The registry stores an appliance with its
//Shelly binding and all eight settings, so a device without them is a registry the service does not
//understand; a Gen2+ plug has no reader yet (none in the house - the reader interface takes one later)
final class ApplianceMapper {

    private ApplianceMapper() {
    }

    sealed interface Mapped {
    }

    record Watched(Appliance appliance) implements Mapped {
    }

    record Skipped(String reason) implements Mapped {
    }

    static Mapped map(final SmartDevice device) {
        final ShellyBinding shelly = device.getShelly();
        if (device.getId() == null || shelly == null || shelly.getAddress() == null || shelly.getGeneration() == null) {
            return new Skipped("no Shelly binding");
        }
        if (device.getAppliance() == null) {
            return new Skipped("no appliance settings");
        }
        if (shelly.getGeneration() >= 2) {
            return new Skipped("a Gen2+ plug is not read yet");
        }
        return new Watched(new Appliance(
            device.getId(),
            device.getName(),
            device.getRoom(),
            shelly.getAddress(),
            Optional.ofNullable(shelly.getChannel()).orElse(0),
            shelly.getGeneration(),
            CycleThresholds.of(device.getAppliance())
        ));
    }
}
