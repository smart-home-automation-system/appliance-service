package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SampleRetentionTest {

    private final ApplianceSampleRepository repository = mock(ApplianceSampleRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-24T01:20:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Warsaw");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final SampleRetention sut = new SampleRetention(repository, new ApplianceProperties(Duration.ofDays(14)), clock);

    //the Mono of a @Scheduled method is subscribed again every night: each night counts back from its own time
    @Test
    void should_delete_the_samples_past_the_retention_counted_from_each_night() {
        when(repository.deleteOlderThan(any())).thenReturn(Mono.just(8640L));
        final Mono<Void> purge = sut.purge();

        purge.as(StepVerifier::create).verifyComplete();
        now.set(now.get().plus(Duration.ofDays(1)));
        purge.as(StepVerifier::create).verifyComplete();

        verify(repository).deleteOlderThan(Instant.parse("2026-10-10T01:20:00Z"));
        verify(repository).deleteOlderThan(Instant.parse("2026-10-11T01:20:00Z"));
    }

    @Test
    void should_complete_when_the_delete_fails() {
        when(repository.deleteOlderThan(any())).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.purge().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(repository.deleteOlderThan(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::purge)
            .expectSubscription()
            .expectNoEvent(SampleRetention.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
