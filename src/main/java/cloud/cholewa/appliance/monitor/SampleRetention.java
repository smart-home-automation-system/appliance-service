package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

//Every night at 03:20 house time the samples older than appliance.sample-retention (14 days) are deleted.
//The time is read inside the pass, which ScheduledPass defers - Spring subscribes again to the same Mono for
//every run. A failed delete is a WARN and is tried again the next night
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleRetention {

    //one DELETE of a day of rows (about 9000) - a pass beyond a quarter of an hour is hung, and ends there
    static final Duration LIMIT = Duration.ofMinutes(15);

    private final ApplianceSampleRepository applianceSampleRepository;
    private final ApplianceProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 20 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("appliance sample retention", LIMIT, this::purgeOnce);
    }

    Mono<Void> purgeOnce() {
        return Mono.defer(() -> {
                final Instant before = Instant.now(clock).minus(properties.sampleRetention());
                return applianceSampleRepository.deleteOlderThan(before)
                    .doOnNext(deleted -> log.info("Appliance samples taken before {} deleted: {}", before, deleted));
            })
            .doOnError(e -> log.warn("Appliance samples past the retention could not be deleted, tried again tomorrow: {}",
                e.toString()))
            .onErrorComplete()
            .then();
    }
}
