package cloud.cholewa.appliance.availability;

import cloud.cholewa.appliance.availability.PlugAvailability.Watched;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.commons.notification.NotificationPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The monitor of the plugs (HAS-280): the messages and when the alerts given move. The house is in Europe/Warsaw
class PlugAvailabilityMonitorTest {

    private static final Instant START = Instant.parse("2026-10-26T17:00:00Z");
    private static final PlugMonitorProperties PROPERTIES = new PlugMonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1));

    private final ApplianceMonitor applianceMonitor = mock(ApplianceMonitor.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START.plus(Duration.ofMinutes(10)));
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
    private final PlugAvailabilityMonitor sut = new PlugAvailabilityMonitor(applianceMonitor, publisher, PROPERTIES, clock);

    //a Wi-Fi outage is one alert naming every plug, on the technical channel
    @Test
    void should_name_every_plug_out_in_one_alert() {
        when(applianceMonitor.plugs()).thenReturn(List.of(
            new Watched(131, "Washing machine", "boiler", START, true),
            new Watched(132, "Dryer", "bathroom up", START, true),
            new Watched(133, "Dishwasher", "kitchen", null, true)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());

        sut.check().as(StepVerifier::create).verifyComplete();
        sut.check().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(1)).publishAlert(text.capture());
        assertThat(text.getValue()).isEqualTo("Appliance plug not answering: Washing machine (boiler, since 2026-10-26 18:00),"
            + " Dryer (bathroom up, since 2026-10-26 18:00) - the end of a cycle cannot be seen.");
        verify(publisher, never()).publishHousehold(anyString());
    }

    @Test
    void should_remind_after_an_hour_and_tell_the_return() {
        final Watched out = new Watched(132, "Dryer", "bathroom up", START, true);
        when(applianceMonitor.plugs()).thenReturn(List.of(out));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());

        sut.check().as(StepVerifier::create).verifyComplete();
        now.set(now.get().plus(Duration.ofHours(1)));
        sut.check().as(StepVerifier::create).verifyComplete();
        verify(publisher).publishReminder("Appliance plug still not answering: Dryer (bathroom up, since 2026-10-26 18:00)");

        when(applianceMonitor.plugs()).thenReturn(List.of(new Watched(132, "Dryer", "bathroom up", null, true)));
        sut.check().as(StepVerifier::create).verifyComplete();
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher, times(1)).publishInfo("Appliance plug answering again: Dryer (bathroom up, out since 2026-10-26 18:00)");
    }

    //a message the broker did not take is due again with the next pass - and the pass never fails
    @Test
    void should_tell_an_alert_again_when_the_broker_refused_it() {
        when(applianceMonitor.plugs()).thenReturn(List.of(new Watched(132, "Dryer", "bathroom up", START, true)));
        when(publisher.publishAlert(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker away")), Mono.empty());

        sut.check().as(StepVerifier::create).verifyComplete();
        sut.check().as(StepVerifier::create).verifyComplete();
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher, times(2)).publishAlert(anyString());
    }

    //a broker that never confirms stalls no pass for longer than the timeout
    @Test
    void should_cut_off_a_message_that_is_never_confirmed() {
        when(applianceMonitor.plugs()).thenReturn(List.of(new Watched(132, "Dryer", "bathroom up", START, true)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::check)
            .expectSubscription()
            .expectNoEvent(PlugAvailabilityMonitor.PUBLISH_TIMEOUT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
