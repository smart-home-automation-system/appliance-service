package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.MemberReportReply;
import cloud.cholewa.appliance.api.model.MemberReportReply.ApplianceCount;
import cloud.cholewa.appliance.api.model.MemberReportReply.Count;
import cloud.cholewa.appliance.api.model.MemberReportReply.Member;
import cloud.cholewa.appliance.api.model.MemberReportReply.Unattributed;
import cloud.cholewa.appliance.config.ExceptionHandlerConfig;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON of the chores report is the contract of the dashboard (HAS-346); the logic is ReportService's
@WebFluxTest(ReportController.class)
@Import(ExceptionHandlerConfig.class)
class ReportControllerTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReportService reportService;

    @Test
    void should_answer_the_report_of_the_members() {
        when(reportService.members(FROM, TO)).thenReturn(Mono.just(new MemberReportReply(FROM, TO,
            List.of(new Member("Anna", List.of(new ApplianceCount(131, "Washing machine", 3, 2.5, 0.0, 0.833),
                new ApplianceCount(132, "Dryer", 0, 0.0, 0.0, null)), new Count(3, 2.5, 0.0, 0.833))),
            List.of(), List.of(new Unattributed(131, "Washing machine", 1)), 4)));

        webTestClient.get().uri("/reports/members?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"from": "2026-10-01T00:00:00", "to": "2026-11-01T00:00:00",
                 "members": [{"member": "Anna", "appliances": [
                    {"applianceId": 131, "name": "Washing machine", "opportunities": 3, "done": 2.5, "doneAway": 0.0, "share": 0.833},
                    {"applianceId": 132, "name": "Dryer", "opportunities": 0, "done": 0.0, "doneAway": 0.0}],
                   "total": {"opportunities": 3, "done": 2.5, "doneAway": 0.0, "share": 0.833}}],
                 "former": [],
                 "unattributed": [{"applianceId": 131, "name": "Washing machine", "count": 1}],
                 "unknownPresence": 4}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_code_of_a_range_that_breaks_its_rules() {
        when(reportService.members(any(), any())).thenReturn(Mono.error(ApplianceException.invalidRange("to must be after from")));

        webTestClient.get().uri("/reports/members?from=2026-11-01T00:00:00&to=2026-10-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE");
    }

    //a time with an offset is not a time of the house
    @Test
    void should_refuse_a_range_with_an_offset() {
        webTestClient.get().uri("/reports/members?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(reportService);
    }
}
