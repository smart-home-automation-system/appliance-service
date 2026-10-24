package cloud.cholewa.appliance.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

//The client of database-service (the device registry). Built from the injected builder, so the calls are
//traced and logged by Logbook (cholewa-commons >= 1.10.0); the timeouts keep a silent database-service
//from holding a scheduled pass until its bound
@Configuration
public class DatabaseServiceClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    @Bean
    WebClient databaseServiceWebClient(final WebClient.Builder webClientBuilder, final InternalServicesProperties services) {
        final HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder
            .baseUrl(services.database().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
