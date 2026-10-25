package cloud.cholewa.appliance.power;

import cloud.cholewa.appliance.registry.Appliance;
import reactor.core.publisher.Mono;

//How the draw of the plug of an appliance is read. One reader today, for the Gen1 plugs of the house; a
//Gen2+ plug would take switch:0.apower from /device (HAS-278) - a second reader behind this interface.
//Never signals an error: a failed poll is a sample that was not answered
public interface PowerReader {

    Mono<PowerSample> read(Appliance appliance);
}
