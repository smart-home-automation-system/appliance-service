package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.ApplianceReply;
import cloud.cholewa.appliance.api.model.RunReply;
import cloud.cholewa.appliance.api.model.SampleReply;
import cloud.cholewa.appliance.config.ExceptionHandlerConfig;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.service.ApplianceService;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the bodies are written out whole; the logic is ApplianceService's
//(ApplianceServiceTest) - here the request, the shape of the answer and the statuses
@WebFluxTest(ApplianceController.class)
@Import(ExceptionHandlerConfig.class)
class ApplianceControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 24, 10, 0);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ApplianceService applianceService;

    @Test
    void should_answer_every_appliance_leaving_out_what_is_not_known() {
        when(applianceService.appliances()).thenReturn(Mono.just(List.of(
            new ApplianceReply(31, "Dryer", RoomName.BOILER, ApplianceState.FINISHED, 0.8, AT.plusSeconds(3800), AT,
                AT.plusHours(1), AT.plusSeconds(3780), true),
            new ApplianceReply(51, "Dishwasher", RoomName.KITCHEN, ApplianceState.IDLE, null, null, null, null, null, null)
        )));

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
    void should_answer_one_appliance() {
        when(applianceService.appliance(51)).thenReturn(Mono.just(
            new ApplianceReply(51, "Dishwasher", RoomName.KITCHEN, ApplianceState.RUNNING, 1987.5, AT.plusMinutes(10), AT,
                null, null, true)));

        webTestClient.get().uri("/51")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"id": 51, "name": "Dishwasher", "room": "kitchen", "state": "running", "watts": 1987.5,
                 "sampledAt": "2026-10-24T10:10:00", "runningSince": "2026-10-24T10:00:00", "reachable": true}""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_an_appliance_it_does_not_watch_with_its_code() {
        when(applianceService.appliance(anyLong())).thenReturn(Mono.error(ApplianceException.notFound(99)));

        webTestClient.get().uri("/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_APPLIANCE");
    }

    @Test
    void should_hand_the_range_of_the_runs_to_the_service() {
        when(applianceService.runs(51, LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0)))
            .thenReturn(Flux.just(new RunReply(3, AT, AT.plusHours(2), 812L, 2140, AT.plusHours(3), "Anna"),
                new RunReply(4, AT.plusHours(10), null, null, null, null, null)));

        webTestClient.get().uri("/51/runs?from=2026-10-01T00:00:00&to=2026-10-25T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 3, "startedAt": "2026-10-24T10:00:00", "finishedAt": "2026-10-24T12:00:00",
                   "energyWh": 812, "maxWatts": 2140, "acknowledgedAt": "2026-10-24T13:00:00", "acknowledgedBy": "Anna"},
                  {"id": 4, "startedAt": "2026-10-24T20:00:00"}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_hand_the_range_of_the_samples_to_the_service() {
        when(applianceService.samples(51, LocalDateTime.of(2026, 10, 24, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0)))
            .thenReturn(Flux.just(new SampleReply(AT, new BigDecimal("511.69"))));

        webTestClient.get().uri("/51/samples?from=2026-10-24T00:00:00&to=2026-10-25T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"at": "2026-10-24T10:00:00", "watts": 511.69}]""", JsonCompareMode.STRICT);
        verify(applianceService).samples(51, LocalDateTime.of(2026, 10, 24, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0));
    }

    @Test
    void should_acknowledge_with_204() {
        when(applianceService.acknowledge(133, null)).thenReturn(Mono.empty());

        webTestClient.post().uri("/133/acknowledge")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();
    }

    //HAS-347: who acknowledges it, handed to the service as sent
    @Test
    void should_hand_the_member_who_acknowledges_to_the_service() {
        when(applianceService.acknowledge(133, "anna")).thenReturn(Mono.empty());

        webTestClient.post().uri("/133/acknowledge?member=anna")
            .exchange()
            .expectStatus().isNoContent();
        verify(applianceService).acknowledge(133, "anna");
    }

    @Test
    void should_refuse_a_member_name_out_of_the_bounds_of_the_registry() {
        webTestClient.post().uri("/133/acknowledge?member=ab")
            .exchange()
            .expectStatus().isBadRequest();
        verifyNoInteractions(applianceService);
    }

    @Test
    void should_answer_400_with_its_code_for_an_unknown_member() {
        when(applianceService.acknowledge(133, "Nobody")).thenReturn(Mono.error(ApplianceException.unknownMember("Nobody")));

        webTestClient.post().uri("/133/acknowledge?member=Nobody")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("UNKNOWN_MEMBER");
    }

    @Test
    void should_answer_409_when_nothing_waits_to_be_acknowledged() {
        when(applianceService.acknowledge(133, null)).thenReturn(Mono.error(ApplianceException.nothingToAcknowledge(133)));

        webTestClient.post().uri("/133/acknowledge")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOTHING_TO_ACKNOWLEDGE");
    }

    //a range the service refuses is a 400
    @Test
    void should_answer_a_refused_range_with_400() {
        when(applianceService.runs(anyLong(), any(), any()))
            .thenReturn(Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from")));

        webTestClient.get().uri("/51/runs?from=2026-10-02T00:00:00&to=2026-10-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();
    }

    //an offset is not the clock of the house - refused before the service is asked
    @Test
    void should_refuse_a_bound_with_an_offset() {
        webTestClient.get().uri("/51/samples?from={from}&to={to}", "2026-10-01T00:00:00+02:00", "2026-10-02T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();
        verifyNoInteractions(applianceService);
    }
}
