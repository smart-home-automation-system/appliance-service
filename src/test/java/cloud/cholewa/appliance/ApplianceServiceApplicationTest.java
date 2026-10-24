package cloud.cholewa.appliance;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApplianceServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 22 connections of the managed database, counted twice
    //during a rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must neither poll the plugs nor publish to the household
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(applicationContext.getEnvironment().getProperty("internal.service.database"))
            .isEqualTo("http://localhost:1");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.port")).isEqualTo("1");
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300): the tasks of HAS-278 run on it
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //the household notifications go out through the publisher of cholewa-commons, which checks at the
    //start that the template confirms, returns and publishes mandatory - the context would not start
    //otherwise. The env of the test is dev, never the queue of the household
    @Test
    void should_publish_the_household_notifications_through_the_publisher_of_the_library() {
        assertThat(applicationContext.getBeanNamesForType(NotificationPublisher.class)).hasSize(1);
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.notification.env")).isEqualTo("dev");
    }

    //the plugs are Gen1 Shelly devices, polled over HTTP with the client of shelly-client (HAS-278)
    @Test
    void should_have_the_gen1_client_of_shelly_client() {
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).hasSize(1);
    }

    @Test
    void should_have_a_client_of_database_service() {
        assertThat(applicationContext.getBean("databaseServiceWebClient")).isInstanceOf(WebClient.class);
    }
}
