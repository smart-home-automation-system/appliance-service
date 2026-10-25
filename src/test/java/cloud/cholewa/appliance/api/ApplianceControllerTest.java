package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.config.ExceptionHandlerConfig;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the bodies are written out whole. The house is in
//Europe/Warsaw: 08:00Z is 10:00 in October
@WebFluxTest(ApplianceController.class)
@Import({ExceptionHandlerConfig.class, ApplianceControllerTest.HouseClock.class})
class ApplianceControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final CycleThresholds THRESHOLDS = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));
    private static final Appliance DISHWASHER = new Appliance(51, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1, THRESHOLDS);
    private static final Appliance DRYER = new Appliance(31, "Dryer", RoomName.BOILER, "192.0.2.31", 0, 1, THRESHOLDS);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ApplianceMonitor applianceMonitor;
    @MockitoBean
    private ApplianceRunRepository applianceRunRepository;
    @MockitoBean
    private ApplianceSampleRepository applianceSampleRepository;

    @Test
    void should_answer_every_appliance_leaving_out_what_is_not_known() {
        when(applianceMonitor.statuses()).thenReturn(List.of(
            new ApplianceStatus(DRYER, new CycleView(ApplianceState.FINISHED, T0, T0.plusSeconds(3600), T0.plusSeconds(3780)),
                0.8, T0.plusSeconds(3800), true),
            new ApplianceStatus(DISHWASHER, new CycleView(ApplianceState.IDLE, null, null, null), null, null, null)
        ));

        webTestClient.get().uri("/")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 31, "name": "Dryer", "room": "boiler", "state": "finished", "watts": 0.8,
                   "sampledAt": "2026-10-24T11:03:20", "runningSince": "2026-10-24T10:00:00",
                   "finishedAt": "2026-10-24T11:00:00", "awaitingSince": "2026-10-24T11:03:00", "reachable": true},
                  {"id": 51, "name": "Dishwasher", "room": "kitchen", "state": "idle"}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_one_appliance_running() {
        when(applianceMonitor.status(51)).thenReturn(Optional.of(new ApplianceStatus(DISHWASHER,
            new CycleView(ApplianceState.RUNNING, T0, null, null), 1987.5, T0.plusSeconds(600), true)));

        webTestClient.get().uri("/51")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"id": 51, "name": "Dishwasher", "room": "kitchen", "state": "running", "watts": 1987.5,
                 "sampledAt": "2026-10-24T10:10:00", "runningSince": "2026-10-24T10:00:00", "reachable": true}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_an_appliance_it_does_not_watch_with_its_code() {
        when(applianceMonitor.status(anyLong())).thenReturn(Optional.empty());

        webTestClient.get().uri("/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_APPLIANCE");
        webTestClient.get().uri("/99/runs?from=2026-10-01T00:00:00&to=2026-10-02T00:00:00")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void should_answer_the_runs_of_a_range_by_the_clock_of_the_house() {
        when(applianceMonitor.status(51)).thenReturn(Optional.of(idle()));
        when(applianceRunRepository.findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(
            51, Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z")))
            .thenReturn(Flux.just(
                ApplianceRunEntity.builder().id(3L).deviceId(51).startedAt(T0).finishedAt(T0.plusSeconds(7200))
                    .energyWh(812L).maxWatts(2140).build(),
                ApplianceRunEntity.builder().id(4L).deviceId(51).startedAt(T0.plusSeconds(36000)).build()));

        webTestClient.get().uri("/51/runs?from=2026-10-01T00:00:00&to=2026-10-25T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 3, "startedAt": "2026-10-24T10:00:00", "finishedAt": "2026-10-24T12:00:00",
                   "energyWh": 812, "maxWatts": 2140},
                  {"id": 4, "startedAt": "2026-10-24T20:00:00"}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_samples_of_a_range() {
        when(applianceMonitor.status(51)).thenReturn(Optional.of(idle()));
        when(applianceSampleRepository.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(
            51, Instant.parse("2026-10-23T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z")))
            .thenReturn(Flux.just(new ApplianceSampleEntity(1L, 51, T0, new BigDecimal("511.69"))));

        webTestClient.get().uri("/51/samples?from=2026-10-24T00:00:00&to=2026-10-25T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"at": "2026-10-24T10:00:00", "watts": 511.69}]""", JsonCompareMode.STRICT);
        verify(applianceSampleRepository).findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(
            51, Instant.parse("2026-10-23T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        // to before from, longer than allowed, an offset the pattern does not take
        "runs, 2026-10-02T00:00:00, 2026-10-01T00:00:00",
        "runs, 2026-09-01T00:00:00, 2026-10-02T00:00:01",
        "samples, 2026-10-01T00:00:00, 2026-10-03T00:00:01",
        "samples, 2026-10-01T00:00:00+02:00, 2026-10-02T00:00:00"
    })
    void should_refuse_a_range_that_breaks_a_rule(final String what, final String from, final String to) {
        when(applianceMonitor.status(51)).thenReturn(Optional.of(idle()));

        webTestClient.get().uri("/51/{what}?from={from}&to={to}", what, from, to)
            .exchange()
            .expectStatus().isBadRequest();
        verifyNoInteractions(applianceRunRepository, applianceSampleRepository);
    }

    private static ApplianceStatus idle() {
        return new ApplianceStatus(DISHWASHER, new CycleView(ApplianceState.IDLE, null, null, null), null, null, null);
    }

    @TestConfiguration
    static class HouseClock {

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneId.of("Europe/Warsaw"));
        }
    }
}
