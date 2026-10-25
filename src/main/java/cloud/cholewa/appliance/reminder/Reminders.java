package cloud.cholewa.appliance.reminder;

import cloud.cholewa.appliance.cycle.RunStore;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.registry.Appliance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;

//The messages of one finished cycle (HAS-279), judged from its row whenever the cycle has just finished and
//every minute after: not told yet - the finish goes at once, quiet hours or not (owner, 2026-10-25); told -
//a reminder every reminderEveryMinutes after the last message, at most maxReminders, never in the quiet hours
//(one due then waits for their end); acknowledged - nothing. The row says what was sent, so a restart of the
//pod resends nothing, and a message that did not get through is sent again with the next check. Message
//first, row after: a failed write repeats the message rather than lose it. Never signals an error
@Slf4j
@Component
@RequiredArgsConstructor
public class Reminders {

    private final RunStore runStore;
    private final HouseholdNotifier householdNotifier;
    private final Clock clock;

    public Mono<Void> check(final Appliance appliance, final long runId) {
        return runStore.find(runId)
            .flatMap(run -> send(appliance, run))
            .onErrorResume(e -> {
                log.warn("The messages of the cycle {} of {} ({}) could not be checked: {}", runId, appliance.name(),
                    appliance.id(), e.toString());
                return Mono.empty();
            })
            .then();
    }

    private Mono<Void> send(final Appliance appliance, final ApplianceRunEntity run) {
        if (run.acknowledgedAt() != null || run.finishedAt() == null) {
            return Mono.empty();
        }
        final Instant now = Instant.now(clock);
        if (run.notifiedAt() == null) {
            //when it happened: the end of the cycle, also for a message repeated after a failure
            return householdNotifier.finished(appliance, run.finishedAt())
                .filter(Boolean::booleanValue)
                .flatMap(sent -> runStore.notified(run.id(), now));
        }
        if (!isReminderDue(appliance.reminders(), run, now)) {
            return Mono.empty();
        }
        final int count = run.remindersSent() + 1;
        return householdNotifier.reminder(appliance, now, count)
            .filter(Boolean::booleanValue)
            .flatMap(sent -> runStore.reminded(run.id(), count, now));
    }

    private boolean isReminderDue(final ReminderSettings settings, final ApplianceRunEntity run, final Instant now) {
        final Instant last = run.lastMessageAt() != null ? run.lastMessageAt() : run.notifiedAt();
        return run.remindersSent() < settings.max()
            && !now.isBefore(last.plus(settings.every()))
            && !settings.isQuiet(LocalTime.ofInstant(now, clock.getZone()));
    }
}
