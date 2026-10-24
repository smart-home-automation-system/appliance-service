package cloud.cholewa.appliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApplianceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplianceServiceApplication.class, args);
    }

}
