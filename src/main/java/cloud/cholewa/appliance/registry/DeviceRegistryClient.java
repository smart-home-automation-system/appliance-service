package cloud.cholewa.appliance.registry;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.SmartDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

//The switched-on appliances of the device registry of database-service. What a failing database-service
//says goes to the log, never further; no body at all, or one that is not a list, is a failed call - never
//an empty registry, which would forget every appliance and its cycle. The shape of the client of
//weather-service
@Slf4j
@Component
public class DeviceRegistryClient {

    private static final ParameterizedTypeReference<List<SmartDevice>> DEVICES = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseServiceWebClient;

    public DeviceRegistryClient(@Qualifier("databaseServiceWebClient") final WebClient databaseServiceWebClient) {
        this.databaseServiceWebClient = databaseServiceWebClient;
    }

    public Mono<List<SmartDevice>> getAppliances() {
        return databaseServiceWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/home/device/registry")
                .queryParam("kind", DeviceKind.APPLIANCE.getValue())
                .queryParam("enabled", true)
                .build()
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the appliances: {}",
                        error.status().value(), error.errors());
                    return new RegistryCallException("database-service answered " + error.status().value());
                }))
            .bodyToMono(DEVICES)
            .switchIfEmpty(Mono.error(() -> new RegistryCallException("database-service answered without a list")));
    }
}
