package cloud.cholewa.appliance.database.repository;

import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

//On the tables V1 and V2 create, in a PostgreSQL in Docker (Testcontainers) - the migrations are the
//PostgreSQL ones the pod runs. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class ApplianceRepositoriesTest {

    //the night the clocks go back in Poland: an instant stays the instant it was
    private static final Instant T0 = Instant.parse("2026-10-25T00:30:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ApplianceRunRepository runs;
    @Autowired
    private ApplianceSampleRepository samples;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetEverything() {
        runs.deleteAll().as(StepVerifier::create).verifyComplete();
        samples.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_run_when_it_starts_and_complete_it_when_it_finishes() {
        final ApplianceRunEntity started = runs.save(ApplianceRunEntity.builder().deviceId(7).startedAt(T0).build()).block();
        assertThat(started.remindersSent()).isZero();

        runs.save(started.toBuilder().finishedAt(T0.plus(Duration.ofHours(2))).energyWh(812L).maxWatts(2140).build()).block();

        runs.findById(started.id()).as(StepVerifier::create)
            .assertNext(run -> {
                assertThat(run.startedAt()).isEqualTo(T0);
                assertThat(run.finishedAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
                assertThat(run.energyWh()).isEqualTo(812L);
                assertThat(run.maxWatts()).isEqualTo(2140);
                assertThat(run.acknowledgedAt()).isNull();
            })
            .verifyComplete();
    }

    //V3: the messages of a finished cycle
    @Test
    void should_store_the_messages_of_a_cycle() {
        final ApplianceRunEntity run = runs.save(ApplianceRunEntity.builder().deviceId(7).startedAt(T0).build()).block();

        runs.save(run.toBuilder().notifiedAt(T0.plusSeconds(60)).lastMessageAt(T0.plusSeconds(1860)).remindersSent(1).build()).block();

        runs.findById(run.id()).as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.notifiedAt()).isEqualTo(T0.plusSeconds(60));
                assertThat(read.lastMessageAt()).isEqualTo(T0.plusSeconds(1860));
                assertThat(read.remindersSent()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void should_find_the_latest_run_of_an_appliance() {
        runs.save(ApplianceRunEntity.builder().deviceId(7).startedAt(T0).build()).block();
        runs.save(ApplianceRunEntity.builder().deviceId(7).startedAt(T0.plus(Duration.ofHours(5))).build()).block();
        runs.save(ApplianceRunEntity.builder().deviceId(8).startedAt(T0.plus(Duration.ofHours(9))).build()).block();

        runs.findFirstByDeviceIdOrderByStartedAtDesc(7).as(StepVerifier::create)
            .assertNext(run -> assertThat(run.startedAt()).isEqualTo(T0.plus(Duration.ofHours(5))))
            .verifyComplete();
    }

    @Test
    void should_find_the_runs_that_started_in_a_range_the_oldest_first() {
        for (int hour : new int[]{30, 0, 10, 20}) {
            runs.save(ApplianceRunEntity.builder().deviceId(7).startedAt(T0.plus(Duration.ofHours(hour))).build()).block();
        }

        runs.findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(7,
                T0.plus(Duration.ofHours(10)), T0.plus(Duration.ofHours(30)))
            .map(ApplianceRunEntity::startedAt)
            .as(StepVerifier::create)
            .expectNext(T0.plus(Duration.ofHours(10)), T0.plus(Duration.ofHours(20)))
            .verifyComplete();
    }

    @Test
    void should_read_the_samples_of_a_range_and_delete_the_old_ones() {
        samples.save(new ApplianceSampleEntity(null, 7, T0, new BigDecimal("511.69"))).block();
        samples.save(new ApplianceSampleEntity(null, 7, T0.plusSeconds(30), new BigDecimal("0.33"))).block();
        samples.save(new ApplianceSampleEntity(null, 8, T0.plusSeconds(30), new BigDecimal("12.00"))).block();

        samples.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(7, T0, T0.plusSeconds(60))
            .map(ApplianceSampleEntity::watts)
            .as(StepVerifier::create)
            .expectNext(new BigDecimal("511.69"), new BigDecimal("0.33"))
            .verifyComplete();

        samples.deleteOlderThan(T0.plusSeconds(30)).as(StepVerifier::create).expectNext(1L).verifyComplete();
        samples.count().as(StepVerifier::create).expectNext(2L).verifyComplete();
    }
}
