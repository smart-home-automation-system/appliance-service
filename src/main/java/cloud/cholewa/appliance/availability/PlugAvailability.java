package cloud.cholewa.appliance.availability;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

//What the availability monitor makes of the plugs and the alerts already given (HAS-280): pure, pinned by
//PlugAvailabilityTest - the rules of the monitor of light-service (HAS-264):
//- a plug is out once it has given no reading (no answer, or an answer without one) for offlineAfter;
//- out and not announced: an alert; announced and still out: a reminder every reminderEvery; announced and reading
//  again: a return; announced and no longer watched (switched off or removed in the registry): forgotten unannounced;
//- a plug not polled since the start says nothing - neither out nor back
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlugAvailability {

    /**
     * A plug of an appliance: since when it gives no reading (null while it reads), and whether it was polled at
     * all since the start.
     */
    public record Watched(long id, String name, String room, Instant failingSince, boolean polled) {
    }

    /** An alert the household had: the plug, since when it was out, when it was last named. */
    public record Alert(long id, String name, String room, Instant since, Instant lastAlertAt) {
    }

    /** What is due: new alerts, reminders, returns, and the alerts of plugs no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {
    }

    public static Decision decide(final List<Watched> watched, final Map<Long, Alert> open, final Instant now,
                                  final PlugMonitorProperties properties) {
        final List<Alert> alerts = watched.stream()
            .filter(plug -> out(plug, now, properties) && !open.containsKey(plug.id()))
            .map(plug -> new Alert(plug.id(), plug.name(), plug.room(), plug.failingSince(), now))
            .toList();
        final List<Alert> reminders = watched.stream()
            .filter(plug -> out(plug, now, properties))
            .map(plug -> open.get(plug.id()))
            .filter(alert -> alert != null && !alert.lastAlertAt().plus(properties.reminderEvery()).isAfter(now))
            .toList();
        final List<Alert> returns = watched.stream()
            .filter(plug -> plug.polled() && plug.failingSince() == null)
            .map(plug -> open.get(plug.id()))
            .filter(Objects::nonNull)
            .toList();
        final Set<Long> ids = watched.stream().map(Watched::id).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !ids.contains(alert.id())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }

    private static boolean out(final Watched plug, final Instant now, final PlugMonitorProperties properties) {
        return plug.failingSince() != null && !plug.failingSince().plus(properties.offlineAfter()).isAfter(now);
    }
}
