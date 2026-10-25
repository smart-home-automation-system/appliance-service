package cloud.cholewa.appliance.monitor;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorScheduleTest {

    private final ApplianceMonitor monitor = mock(ApplianceMonitor.class);
    private final MonitorSchedule sut = new MonitorSchedule(monitor);

    //a poll hung on a dead plug ends at its bound, below the 30 s between polls
    @Test
    void should_end_a_sampling_pass_that_never_completes_at_its_bound() {
        when(monitor.sample()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::sample)
            .expectSubscription()
            .expectNoEvent(MonitorSchedule.SAMPLE_LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    @Test
    void should_end_a_reminder_pass_that_never_completes_at_its_bound() {
        when(monitor.remind()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::remind)
            .expectSubscription()
            .expectNoEvent(MonitorSchedule.REMIND_LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    @Test
    void should_end_a_refresh_that_never_completes_at_its_bound() {
        when(monitor.refresh()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::refresh)
            .expectSubscription()
            .expectNoEvent(MonitorSchedule.REFRESH_LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    @Test
    void should_bound_each_pass_below_its_delay() {
        assertThat(Duration.ofSeconds(30)).isGreaterThan(MonitorSchedule.SAMPLE_LIMIT);
        assertThat(Duration.ofMinutes(5)).isGreaterThan(MonitorSchedule.REFRESH_LIMIT);
        assertThat(Duration.ofMinutes(1)).isGreaterThan(MonitorSchedule.REMIND_LIMIT);
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_build_the_passes_anew_for_every_run() {
        when(monitor.sample()).thenReturn(Mono.empty());
        when(monitor.refresh()).thenReturn(Mono.empty());

        final Mono<Void> sample = sut.sample();
        final Mono<Void> refresh = sut.refresh();
        sample.as(StepVerifier::create).verifyComplete();
        sample.as(StepVerifier::create).verifyComplete();
        refresh.as(StepVerifier::create).verifyComplete();
        refresh.as(StepVerifier::create).verifyComplete();

        verify(monitor, times(2)).sample();
        verify(monitor, times(2)).refresh();
    }
}
