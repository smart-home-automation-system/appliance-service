package cloud.cholewa.appliance.presence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-344: who is at home, as a snapshot that never fails
class PresenceClientTest {

    private static final Instant T0 = Instant.parse("2026-10-30T16:32:35Z");

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private final MutableClock clock = new MutableClock(T0);
    private MockWebServer server;
    private PresenceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new PresenceClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build(), clock);
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(PresenceClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PresenceClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_take_the_names_of_the_residents_at_home() throws InterruptedException {
        server.enqueue(json("""
            [{"name":"Tobiasz","present":true,"since":"2026-10-30T16:24:51"},
             {"name":"Katarzyna","present":false},
             {"name":"Krzysztof","present":true},
             {"name":"Liwia"}]
            """));

        client.snapshot().as(StepVerifier::create)
            .expectNext(PresenceSnapshot.of(List.of("Krzysztof", "Tobiasz")))
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/presence/residents/presence");
    }

    @Test
    void should_answer_nobody_when_nobody_is_at_home() {
        server.enqueue(json("""
            [{"name":"Tobiasz","present":false}]
            """));

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.of(List.of())).verifyComplete();
    }

    @Test
    void should_keep_an_answer_for_a_minute() {
        server.enqueue(json("[{\"name\":\"Tobiasz\",\"present\":true}]"));
        server.enqueue(json("[{\"name\":\"Liwia\",\"present\":true}]"));

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.of(List.of("Tobiasz"))).verifyComplete();
        clock.set(T0.plusSeconds(59));
        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.of(List.of("Tobiasz"))).verifyComplete();
        clock.set(T0.plusSeconds(60));
        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.of(List.of("Liwia"))).verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_answer_not_known_when_presence_service_fails() {
        server.enqueue(new MockResponse.Builder().code(503)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"Unavailable\"}]}").build());

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.unknown()).verifyComplete();

        assertThat(clientLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    //an empty list says nothing of who is at home - right after a start of presence-service
    @Test
    void should_answer_not_known_for_an_empty_list_and_not_keep_it() {
        server.enqueue(json("[]"));
        server.enqueue(json("[{\"name\":\"Tobiasz\",\"present\":true}]"));

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.unknown()).verifyComplete();
        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.of(List.of("Tobiasz"))).verifyComplete();
    }

    @Test
    void should_answer_not_known_when_presence_service_is_silent() {
        server.enqueue(new MockResponse.Builder().headersDelay(PresenceClient.READ_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body("[]").build());

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.unknown()).verifyComplete();
    }

    @Test
    void should_answer_not_known_for_a_body_that_is_not_the_list() {
        server.enqueue(json("{}"));

        client.snapshot().as(StepVerifier::create).expectNext(PresenceSnapshot.unknown()).verifyComplete();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void set(final Instant at) {
            now = at;
        }

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
            return now;
        }
    }
}
