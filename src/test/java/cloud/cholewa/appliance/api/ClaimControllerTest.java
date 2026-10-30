package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.ClaimReply;
import cloud.cholewa.appliance.api.model.ClaimRequest;
import cloud.cholewa.appliance.config.ExceptionHandlerConfig;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.service.ClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON is the contract of the tag page of the dashboard (HAS-346); the logic is ClaimService's
@WebFluxTest(ClaimController.class)
@Import(ExceptionHandlerConfig.class)
class ClaimControllerTest {

    private static final String BODY = """
        {"piccData": "ef963ff7828658a599f3041510671e88", "cmac": "94EED9EE65337086", "member": "Anna"}""";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ClaimService claimService;

    @Test
    void should_answer_the_claim() {
        when(claimService.claim(new ClaimRequest("ef963ff7828658a599f3041510671e88", "94EED9EE65337086", "Anna")))
            .thenReturn(Mono.just(new ClaimReply(51, "Dishwasher", ApplianceState.IDLE, "Anna", 77,
                LocalDateTime.of(2026, 10, 24, 10, 0))));

        post(BODY)
            .expectStatus().isOk()
            .expectBody().json("""
                {"applianceId": 51, "name": "Dishwasher", "state": "idle", "member": "Anna", "runId": 77,
                 "claimedAt": "2026-10-24T10:00:00"}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_code_of_a_refused_tag() {
        when(claimService.claim(any())).thenReturn(Mono.error(ApplianceException.replayedTag("04DE5F1EACC040")));

        post(BODY)
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("REPLAYED_TAG");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        """
            {"piccData": "ef963ff7828658a599f3041510671e", "cmac": "94EED9EE65337086", "member": "Anna"}""",
        """
            {"uid": "04DE5F1EACC040", "counter": "00003D", "cmac": "94EED9EE65337086", "member": "Anna"}""",
        """
            {"piccData": "ef963ff7828658a599f3041510671e88", "cmac": "94EED9EE6533708Z", "member": "Anna"}""",
        """
            {"piccData": "ef963ff7828658a599f3041510671e88", "cmac": "94EED9EE65337086"}""",
        """
            {"piccData": "ef963ff7828658a599f3041510671e88", "cmac": "94EED9EE65337086", "member": "An"}"""
    })
    void should_refuse_a_body_that_is_not_a_tag_read(final String body) {
        post(body).expectStatus().isBadRequest();

        verifyNoInteractions(claimService);
    }

    private WebTestClient.ResponseSpec post(final String body) {
        return webTestClient.post().uri("/claims")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }
}
