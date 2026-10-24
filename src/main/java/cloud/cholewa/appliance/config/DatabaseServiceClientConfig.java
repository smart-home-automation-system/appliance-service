package cloud.cholewa.appliance.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.time.Duration;

//The client of database-service (the device registry). Built from the injected builder, so the calls are
//traced and logged by Logbook (cholewa-commons >= 1.10.0); the timeouts keep a silent database-service
//from holding a scheduled pass until its bound
@Configuration
public class DatabaseServiceClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    //Without a limit an idle connection stays in the pool for ever: after a restart of database-service,
    //or once conntrack has forgotten it, the next call is written onto a dead socket (the lesson of the
    //gateway, HAS-212). The same limits as there and in weather-, blinds- and light-service
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseServiceConnectionProvider() {
        return connectionProvider("database-service");
    }

    @Bean
    WebClient databaseServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return webClient(webClientBuilder, databaseServiceConnectionProvider, services.database(), RESPONSE_TIMEOUT);
    }

    static ConnectionProvider connectionProvider(final String name) {
        return ConnectionProvider.builder(name)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    static WebClient webClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider connectionProvider,
        final URI baseUrl,
        final Duration responseTimeout
    ) {
        final HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(responseTimeout);

        return webClientBuilder
            .baseUrl(baseUrl.toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
