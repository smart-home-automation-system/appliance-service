package cloud.cholewa.appliance.reminder;

import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

//The household category (HAS-279): a HouseholdNotification of smart-home-sdk as JSON through the publisher of
//cholewa-commons; notification-service words it in Polish and sends it to Discord, by SMS and by push. The
//subject is the name of the appliance as the registry has it. A message must neither fail nor stall what it
//reports on: every failure - the broker, no confirm within LIMIT - is a WARN and answers false, and the caller
//sends it again later
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseholdNotifier {

    //where the household acknowledges it in the application
    static final String ACKNOWLEDGE_PATH = "/room";
    static final Duration LIMIT = Duration.ofSeconds(30);

    private final NotificationPublisher notificationPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public Mono<Boolean> finished(final Appliance appliance, final Instant at) {
        return publish(appliance, HouseholdEvent.APPLIANCE_FINISHED, at, 0, ACKNOWLEDGE_PATH, null);
    }

    public Mono<Boolean> reminder(final Appliance appliance, final Instant at, final int count) {
        return publish(appliance, HouseholdEvent.APPLIANCE_REMINDER, at, count, ACKNOWLEDGE_PATH, null);
    }

    //nothing left to acknowledge, so no path; the number of reminders that had been sent and who acknowledged
    //it (HAS-347, left out when nobody was named)
    public Mono<Boolean> acknowledged(final Appliance appliance, final Instant at, final int remindersSent,
                                      final String member) {
        return publish(appliance, HouseholdEvent.APPLIANCE_ACKNOWLEDGED, at, remindersSent, null, member);
    }

    private Mono<Boolean> publish(final Appliance appliance, final HouseholdEvent event, final Instant at,
                                  final int reminder, final String acknowledgePath, final String member) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(HouseholdNotification.builder()
                .event(event)
                .subject(appliance.name())
                .deviceId(appliance.id())
                .occurredAt(LocalDateTime.ofInstant(at, clock.getZone()))
                .reminder(reminder)
                .acknowledgePath(acknowledgePath)
                .member(member)
                .build()))
            .flatMap(notificationPublisher::publishHousehold)
            .timeout(LIMIT)
            .thenReturn(true)
            .onErrorResume(e -> {
                log.warn("The household could not be told '{}' of {} ({}): {}", event.getValue(), appliance.name(),
                    appliance.id(), e.toString());
                return Mono.just(false);
            });
    }
}
