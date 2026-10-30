package cloud.cholewa.appliance.presence;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

//Who is at home (HAS-344): GET /home/presence/residents/presence of presence-service, the names it answers as
//present. A snapshot never fails and never holds anything back: a failed call, a call that takes longer than
//READ_TIMEOUT, an answer that is not the list or an empty list is an unknown snapshot - a WARN, and the cycle or the
//confirmation goes on without it. An answer is kept for CACHED_FOR: a claim and a confirmation a moment apart share it
@Slf4j
@Component
public class PresenceClient {

    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration CACHED_FOR = Duration.ofSeconds(60);

    private static final ParameterizedTypeReference<List<ResidentPresence>> RESIDENTS = new ParameterizedTypeReference<>() {
    };

    private final WebClient presenceServiceWebClient;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public PresenceClient(@Qualifier("presenceServiceWebClient") final WebClient presenceServiceWebClient,
                          final Clock clock) {
        this.presenceServiceWebClient = presenceServiceWebClient;
        this.clock = clock;
    }

    public Mono<PresenceSnapshot> snapshot() {
        return Mono.defer(() -> {
            final Instant now = Instant.now(clock);
            final Cached last = cached.get();
            return last != null && last.at().plus(CACHED_FOR).isAfter(now)
                ? Mono.just(last.snapshot())
                : read(now);
        });
    }

    private Mono<PresenceSnapshot> read(final Instant now) {
        return presenceServiceWebClient
            .get()
            .uri("/home/presence/residents/presence")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> new IllegalStateException("presence-service answered " + error.status().value())))
            .bodyToMono(RESIDENTS)
            .timeout(READ_TIMEOUT)
            .filter(residents -> !residents.isEmpty())
            .map(residents -> PresenceSnapshot.of(residents.stream()
                .filter(resident -> resident.name() != null && Boolean.TRUE.equals(resident.present()))
                .map(ResidentPresence::name)
                .sorted()
                .toList()))
            .doOnNext(snapshot -> cached.set(new Cached(now, snapshot)))
            .switchIfEmpty(Mono.fromSupplier(() -> {
                log.warn("presence-service answered without residents: who is at home is not known");
                return PresenceSnapshot.unknown();
            }))
            .onErrorResume(e -> {
                log.warn("Who is at home could not be read, the snapshot is not known: {}", e.toString());
                return Mono.just(PresenceSnapshot.unknown());
            });
    }

    //an entry of the answer; present is a Boolean so that a missing field is not read as away
    record ResidentPresence(String name, Boolean present) {
    }

    private record Cached(Instant at, PresenceSnapshot snapshot) {
    }
}
