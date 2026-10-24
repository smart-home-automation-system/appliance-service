package cloud.cholewa.appliance.config;

import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

//The error contract of the organization (cholewa-commons); the domain exceptions and their codes come
//with the endpoints (HAS-279)
@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    public GlobalErrorExceptionHandler globalErrorExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        return new GlobalErrorExceptionHandler(
            errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer
        );
    }
}
