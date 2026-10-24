package cloud.cholewa.appliance.config;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseServiceClientConfigTest {

    private MockWebServer server;
    private final ConnectionProvider connectionProvider = DatabaseServiceClientConfig.connectionProvider("test");

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        connectionProvider.dispose();
    }

    @Test
    void should_call_the_address_of_database_service() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(200).body("ok").build());

        client(Duration.ofSeconds(5)).get().uri("/home/device/registry")
                .retrieve().bodyToMono(String.class).as(StepVerifier::create)
            .expectNext("ok")
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/device/registry");
    }

    //without the response timeout a database-service that accepts the connection and never answers
    //would hold the call until the bound of the scheduled pass
    @Test
    void should_give_up_on_a_database_service_that_does_not_answer_in_time() {
        server.enqueue(new MockResponse.Builder().code(200).body("late")
            .headersDelay(3, TimeUnit.SECONDS).build());

        client(Duration.ofSeconds(1)).get().uri("/")
                .retrieve().bodyToMono(String.class).as(StepVerifier::create)
            .expectError(WebClientRequestException.class)
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void should_answer_within_five_seconds() {
        assertThat(DatabaseServiceClientConfig.RESPONSE_TIMEOUT).hasSeconds(5);
    }

    //an idle connection to a restarted database-service must not be reused for ever
    @Test
    void should_retire_idle_and_old_connections_of_the_pool() {
        assertThat(DatabaseServiceClientConfig.MAX_IDLE_TIME).hasMinutes(2);
        assertThat(DatabaseServiceClientConfig.MAX_LIFE_TIME).hasMinutes(30);
    }

    private WebClient client(final Duration responseTimeout) {
        return DatabaseServiceClientConfig.webClient(
            WebClient.builder(),
            connectionProvider,
            URI.create("http://localhost:" + server.getPort()),
            responseTimeout
        );
    }
}
