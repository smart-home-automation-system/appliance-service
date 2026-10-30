package cloud.cholewa.appliance.registry;

import cloud.cholewa.home.model.HouseholdProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

//The names of the switched-on members of the household for the chores report (HAS-345), kept CACHED_FOR - the report
//is read now and then, the registry changes seldom. A failed read answers the last list it had, however old, with a
//WARN; with none the error goes on. The member checks of a claim and an acknowledgement read the profiles fresh
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseholdMembers {

    static final Duration CACHED_FOR = Duration.ofMinutes(5);

    private final HouseholdProfilesClient householdProfilesClient;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public Mono<List<String>> names() {
        return Mono.defer(() -> {
            final Instant now = Instant.now(clock);
            final Cached last = cached.get();
            if (last != null && last.at().plus(CACHED_FOR).isAfter(now)) {
                return Mono.just(last.names());
            }
            return householdProfilesClient.getProfiles()
                .map(profiles -> profiles.stream().map(HouseholdProfile::getName).toList())
                .doOnNext(names -> cached.set(new Cached(now, names)))
                .onErrorResume(e -> last != null, e -> {
                    log.warn("The household profiles could not be read, the report lists the members read at {}: {}",
                        last.at(), e.toString());
                    return Mono.just(last.names());
                });
        });
    }

    private record Cached(Instant at, List<String> names) {
    }
}
