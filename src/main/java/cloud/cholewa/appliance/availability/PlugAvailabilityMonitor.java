package cloud.cholewa.appliance.availability;

import cloud.cholewa.appliance.availability.PlugAvailability.Alert;
import cloud.cholewa.appliance.availability.PlugAvailability.Decision;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.commons.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

//The availability monitor of the plugs (HAS-280): a plug that gives no reading hides the end of a cycle, so it is
//announced on the technical channel - alert and info of notification-service, English, never the household's.
//Every minute: one message per pass and kind naming every plug (a Wi-Fi outage is one alert, not three), a
//reminder every reminderEvery, one info for the returns. A message must neither fail nor stall the pass: each is
//cut off after PUBLISH_TIMEOUT, and what was not published is due again with the next pass - the alerts given
//move only once the broker took the message. They live in memory, as the outage of the Shelly of boiler-service:
//after a restart an outage still going on is announced again (a duplicate, never a silence), and the return of
//one announced before the restart is not
@Slf4j
@Component
@RequiredArgsConstructor
public class PlugAvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ApplianceMonitor applianceMonitor;
    private final NotificationPublisher notificationPublisher;
    private final PlugMonitorProperties properties;
    private final Clock clock;
    private final Map<Long, Alert> open = new ConcurrentHashMap<>();

    public Mono<Void> check() {
        return Mono.fromSupplier(clock::instant)
            .map(now -> new Pass(now, PlugAvailability.decide(applianceMonitor.plugs(), Map.copyOf(open), now, properties)))
            .doOnNext(pass -> pass.decision().forgotten().forEach(alert -> open.remove(alert.id())))
            .flatMap(pass -> publish(pass.decision().alerts(), notificationPublisher::publishAlert,
                    alerts -> "Appliance plug not answering: " + named(alerts, alert -> "since " + time(alert.since()))
                        + " - the end of a cycle cannot be seen.",
                    alerts -> alerts.forEach(alert -> open.put(alert.id(), alert)))
                .then(publish(pass.decision().reminders(), notificationPublisher::publishReminder,
                    alerts -> "Appliance plug still not answering: " + named(alerts, alert -> "since " + time(alert.since())),
                    alerts -> alerts.forEach(alert -> open.put(alert.id(),
                        new Alert(alert.id(), alert.name(), alert.room(), alert.since(), pass.now())))))
                .then(publish(pass.decision().returns(), notificationPublisher::publishInfo,
                    alerts -> "Appliance plug answering again: "
                        + named(alerts, alert -> "out since " + time(alert.since())),
                    alerts -> alerts.forEach(alert -> open.remove(alert.id())))));
    }

    //one message for every plug of the list; nothing to say, nothing sent
    private Mono<Void> publish(final List<Alert> alerts, final Function<String, Mono<Void>> send,
                               final Function<List<Alert>, String> text, final Consumer<List<Alert>> done) {
        return Mono.just(alerts)
            .filter(list -> !list.isEmpty())
            .flatMap(list -> Mono.defer(() -> send.apply(text.apply(list)))
                .timeout(PUBLISH_TIMEOUT)
                .then(Mono.fromRunnable(() -> {
                    log.info("{}", text.apply(list));
                    done.accept(list);
                }))
                .onErrorResume(e -> {
                    log.warn("The availability of the appliance plugs could not be told, tried again with the next pass: {}",
                        e.toString());
                    return Mono.empty();
                }))
            .then();
    }

    private static String named(final List<Alert> alerts, final Function<Alert, String> detail) {
        return alerts.stream()
            .map(alert -> alert.name() + " (" + alert.room() + ", " + detail.apply(alert) + ")")
            .collect(Collectors.joining(", "));
    }

    private String time(final Instant at) {
        return LocalDateTime.ofInstant(at, clock.getZone()).format(TIME);
    }

    private record Pass(Instant now, Decision decision) {
    }
}
