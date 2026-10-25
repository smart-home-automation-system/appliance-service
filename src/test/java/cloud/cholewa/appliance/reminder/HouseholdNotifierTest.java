package cloud.cholewa.appliance.reminder;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The JSON is the contract of notification-service (HouseholdNotificationReader), written out whole
class HouseholdNotifierTest {

    private static final Instant AT = Instant.parse("2026-10-24T10:00:00Z");
    private static final Appliance DISHWASHER = new Appliance(133, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1,
        new CycleThresholds(10, 5, Duration.ofMinutes(30), Duration.ofMinutes(10)),
        new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0)));

    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final HouseholdNotifier sut = new HouseholdNotifier(publisher, JsonMapper.builder().build(),
        Clock.fixed(AT, ZoneId.of("Europe/Warsaw")));

    @Test
    void should_publish_the_finish_with_the_path_to_acknowledge_it() {
        when(publisher.publishHousehold(anyString())).thenReturn(Mono.empty());

        sut.finished(DISHWASHER, AT).as(StepVerifier::create).expectNext(true).verifyComplete();

        assertThat(published()).isEqualTo("""
            {"event":"appliance finished","subject":"Dishwasher","deviceId":133,\
            "occurredAt":"2026-10-24T12:00:00","reminder":0,"acknowledgePath":"/room"}""");
    }

    @Test
    void should_publish_a_reminder_with_its_number() {
        when(publisher.publishHousehold(anyString())).thenReturn(Mono.empty());

        sut.reminder(DISHWASHER, AT, 2).as(StepVerifier::create).expectNext(true).verifyComplete();

        assertThat(published()).contains("\"event\":\"appliance reminder\"", "\"reminder\":2", "\"acknowledgePath\":\"/room\"");
    }

    //nothing left to acknowledge: no path
    @Test
    void should_publish_the_acknowledgement_without_a_path() {
        when(publisher.publishHousehold(anyString())).thenReturn(Mono.empty());

        sut.acknowledged(DISHWASHER, AT, 3).as(StepVerifier::create).expectNext(true).verifyComplete();

        assertThat(published()).contains("\"event\":\"appliance acknowledged\"", "\"reminder\":3")
            .doesNotContain("acknowledgePath");
    }

    @Test
    void should_answer_false_when_the_broker_refuses() {
        when(publisher.publishHousehold(anyString())).thenReturn(Mono.error(new IllegalStateException("not routed")));

        sut.finished(DISHWASHER, AT).as(StepVerifier::create).expectNext(false).verifyComplete();
    }

    //a message must not stall what it reports on
    @Test
    void should_give_up_on_a_confirm_that_never_comes() {
        when(publisher.publishHousehold(anyString())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.finished(DISHWASHER, AT))
            .expectSubscription()
            .thenAwait(HouseholdNotifier.LIMIT)
            .expectNext(false)
            .verifyComplete();
    }

    private String published() {
        final ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishHousehold(json.capture());
        return json.getValue();
    }
}
