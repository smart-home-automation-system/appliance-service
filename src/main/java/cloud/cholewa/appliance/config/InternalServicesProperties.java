package cloud.cholewa.appliance.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * The services this one calls inside the cluster, by k8s DNS. {@code database} - database-service,
 * the device registry the appliances (the kind {@code appliance}, their plugs and settings) are read
 * from. No default: a made-up address would start the service and fail every read.
 */
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database) {
}
