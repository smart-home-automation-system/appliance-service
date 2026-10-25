package cloud.cholewa.appliance.monitor;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The two passes of the monitor, on the scheduler pool of cholewa-commons, each bounded below its delay
//(ScheduledPass, HAS-300). fixedDelay: the next pass waits for the last one to end. Spring subscribes again
//to the same Mono for every run - ScheduledPass defers the pass. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class MonitorSchedule {

    //three plugs read at once, each call bounded by the timeouts of shelly-client, then a few rows: a pass
    //beyond 25 s is hung, and ends there - the next one reads again 30 s later
    static final Duration SAMPLE_LIMIT = Duration.ofSeconds(25);
    //one read of the registry with a 5 s response timeout, and the latest run of a new appliance
    static final Duration REFRESH_LIMIT = Duration.ofMinutes(4);

    private final ApplianceMonitor applianceMonitor;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("appliance registry refresh", REFRESH_LIMIT, applianceMonitor::refresh);
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT30S")
    Mono<Void> sample() {
        return ScheduledPass.bounded("appliance power sampling", SAMPLE_LIMIT, applianceMonitor::sample);
    }
}
