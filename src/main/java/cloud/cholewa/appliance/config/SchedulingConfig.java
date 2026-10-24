package cloud.cholewa.appliance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//Off under the test profile: a @SpringBootTest context would poll the plugs of the house and publish to
//the household (the lesson of water-service, HAS-307). The tasks come with HAS-278 - each wrapped in
//ScheduledPass.bounded of cholewa-commons, on the scheduler pool of the library
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
