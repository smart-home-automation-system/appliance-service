package cloud.cholewa.appliance.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//The appliances of the registry the service can watch. A failed read is a WARN and answers nothing - the
//caller keeps what it had. A device the service cannot watch is a WARN once, until it changes or is gone
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplianceRegistry {

    private final DeviceRegistryClient deviceRegistryClient;
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    public Mono<List<Appliance>> read() {
        return deviceRegistryClient.getAppliances()
            .map(devices -> {
                final List<Appliance> watched = new ArrayList<>();
                final Set<String> skipped = ConcurrentHashMap.newKeySet();
                devices.forEach(device -> {
                    switch (ApplianceMapper.map(device)) {
                        case ApplianceMapper.Watched(Appliance appliance) -> watched.add(appliance);
                        case ApplianceMapper.Skipped(String reason) -> {
                            final String key = device.getId() + ": " + reason;
                            skipped.add(key);
                            if (said.add(key)) {
                                log.warn("Appliance {} of the registry is not watched: {}", device.getId(), reason);
                            }
                        }
                    }
                });
                said.retainAll(skipped);
                return List.copyOf(watched);
            })
            .onErrorResume(e -> {
                log.warn("The appliances of the registry could not be read, the last ones are kept: {}", e.toString());
                return Mono.empty();
            });
    }
}
