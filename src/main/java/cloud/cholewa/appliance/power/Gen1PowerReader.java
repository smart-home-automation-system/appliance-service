package cloud.cholewa.appliance.power;

import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.Gen1Meter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

//GET /meter/<channel> of a Gen1 plug (Plug S, firmware 1.14.0). The time of a sample is the pod's: the
//timestamp of the plug is its local time written as if it were UTC. A plug that does not answer is a
//DEBUG per poll - every 30 s, and the availability monitor of HAS-280 is what tells the household
@Slf4j
@Component
@RequiredArgsConstructor
public class Gen1PowerReader implements PowerReader {

    private final ShellyGen1Client shellyGen1Client;
    private final Clock clock;

    @Override
    public Mono<PowerSample> read(final Appliance appliance) {
        return Mono.defer(() -> shellyGen1Client.meter(appliance.address(), appliance.channel())
            .map(meter -> sample(now(), meter))
            .onErrorResume(e -> {
                log.debug("The plug of {} ({}) did not answer: {}", appliance.name(), appliance.id(), e.toString());
                return Mono.just(PowerSample.unanswered(now()));
            }));
    }

    //whole seconds: the samples are 30 s apart, and the API answers the clock of the house to the second
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    static PowerSample sample(final Instant at, final Gen1Meter meter) {
        if (!Boolean.TRUE.equals(meter.getIsValid()) || meter.getPower() == null) {
            return PowerSample.invalid(at);
        }
        return PowerSample.reading(at, meter.getPower(), meter.getTotal());
    }
}
