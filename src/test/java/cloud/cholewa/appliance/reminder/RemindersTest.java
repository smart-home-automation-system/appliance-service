package cloud.cholewa.appliance.reminder;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.RunStore;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The house is in Europe/Warsaw (UTC+2 on 2026-10-24): 10:00Z is 12:00, 21:30Z is 23:30 - quiet
class RemindersTest {

    private static final Instant NOON = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant NIGHT = Instant.parse("2026-10-24T21:30:00Z");
    //the end of every cycle of the test: the time the finish message carries, whenever it goes
    private static final Instant END = NOON.minus(Duration.ofHours(1));
    private static final Appliance DISHWASHER = new Appliance(133, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1,
        new CycleThresholds(10, 5, Duration.ofMinutes(30), Duration.ofMinutes(10)),
        new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0)));

    private final RunStore runStore = mock(RunStore.class);
    private final HouseholdNotifier notifier = mock(HouseholdNotifier.class);

    @Test
    void should_announce_a_finished_cycle_not_announced_yet() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().build()));
        when(notifier.finished(DISHWASHER, END)).thenReturn(Mono.just(true));
        when(runStore.notified(5L, NOON)).thenReturn(Mono.empty());

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verify(runStore).notified(5L, NOON);
    }

    //owner 2026-10-25: the finish goes at once, quiet hours or not
    @Test
    void should_announce_a_finish_in_the_quiet_hours() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().build()));
        when(notifier.finished(DISHWASHER, END)).thenReturn(Mono.just(true));
        when(runStore.notified(5L, NIGHT)).thenReturn(Mono.empty());

        at(NIGHT).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verify(notifier).finished(DISHWASHER, END);
    }

    //a message that did not get through leaves the row as it was: the next check sends it again
    @Test
    void should_keep_the_row_when_the_message_did_not_get_through() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().build()));
        when(notifier.finished(DISHWASHER, END)).thenReturn(Mono.just(false));

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verify(runStore, never()).notified(anyLong(), any());
    }

    @Test
    void should_remind_once_the_interval_since_the_last_message_has_passed() {
        final Instant last = NOON.minus(Duration.ofMinutes(30));
        when(runStore.find(5L)).thenReturn(Mono.just(run().notifiedAt(last.minusSeconds(1800)).lastMessageAt(last)
            .remindersSent(1).build()));
        when(notifier.reminder(DISHWASHER, NOON, 2)).thenReturn(Mono.just(true));
        when(runStore.reminded(5L, 2, NOON)).thenReturn(Mono.empty());

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verify(runStore).reminded(5L, 2, NOON);
    }

    @Test
    void should_not_remind_before_the_interval() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().notifiedAt(NOON.minusSeconds(1790))
            .lastMessageAt(NOON.minusSeconds(1790)).build()));

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(notifier);
    }

    @Test
    void should_stop_after_the_most_reminders() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().notifiedAt(NOON.minusSeconds(9000))
            .lastMessageAt(NOON.minusSeconds(3600)).remindersSent(4).build()));

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(notifier);
    }

    //a reminder due in the quiet hours waits for their end
    @Test
    void should_hold_a_reminder_in_the_quiet_hours() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().notifiedAt(NIGHT.minusSeconds(3600))
            .lastMessageAt(NIGHT.minusSeconds(3600)).build()));

        at(NIGHT).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(notifier);
    }

    @Test
    void should_send_nothing_for_an_acknowledged_cycle() {
        when(runStore.find(5L)).thenReturn(Mono.just(run().notifiedAt(NOON.minusSeconds(3600)).acknowledgedAt(NOON).build()));

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(notifier);
    }

    @Test
    void should_complete_when_the_row_cannot_be_read() {
        when(runStore.find(5L)).thenReturn(Mono.error(new IllegalStateException("database away")));

        at(NOON).check(DISHWASHER, 5L).as(StepVerifier::create).verifyComplete();

        verify(notifier, never()).reminder(any(), any(), anyInt());
    }

    private Reminders at(final Instant now) {
        return new Reminders(runStore, notifier, Clock.fixed(now, ZoneId.of("Europe/Warsaw")));
    }

    private static ApplianceRunEntity.ApplianceRunEntityBuilder run() {
        return ApplianceRunEntity.builder().id(5L).deviceId(133).startedAt(NOON.minus(Duration.ofHours(3)))
            .finishedAt(END);
    }
}
