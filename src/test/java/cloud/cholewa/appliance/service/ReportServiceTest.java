package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.api.model.MemberReportReply.ApplianceCount;
import cloud.cholewa.appliance.api.model.MemberReportReply.Count;
import cloud.cholewa.appliance.api.model.MemberReportReply.Member;
import cloud.cholewa.appliance.api.model.MemberReportReply.Unattributed;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.database.repository.MemberReportRepository;
import cloud.cholewa.appliance.database.repository.MemberReportRepository.ReportRow;
import cloud.cholewa.appliance.error.ApplianceError;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.registry.HouseholdMembers;
import cloud.cholewa.appliance.registry.RegistryCallException;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The report put together from the rows of the aggregation (its SQL is pinned on PostgreSQL in ApplianceRepositoriesTest).
//The house is in Europe/Warsaw: October 2026 starts at 2026-09-30T22:00Z
class ReportServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final CycleThresholds THRESHOLDS = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));
    private static final ReminderSettings REMINDERS = new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0));

    private final MemberReportRepository repository = mock(MemberReportRepository.class);
    private final HouseholdMembers members = mock(HouseholdMembers.class);
    private final ApplianceMonitor monitor = mock(ApplianceMonitor.class);
    private final ReportService sut = new ReportService(repository, members, monitor,
        Clock.fixed(Instant.parse("2026-10-30T18:00:00Z"), ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(monitor.statuses()).thenReturn(List.of(watched(131, "Washing machine"), watched(132, "Dryer")));
        when(members.names()).thenReturn(Mono.just(List.of("Anna", "Jan", "Ola")));
    }

    @Test
    void should_report_every_member_on_every_appliance_with_the_former_apart() {
        when(repository.report(Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-31T23:00:00Z")))
            .thenReturn(Flux.just(
                member(131, "Anna", 3, "2.50", "0.00"),
                member(131, "jan", 3, "0.50", "1.00"),
                member(133, "Jan", 1, "1.00", "0.00"),
                member(132, "Piotr", 2, "1.00", "0.00"),
                new ReportRow("unattributed", 131L, null, 1L, BigDecimal.ZERO, BigDecimal.ZERO),
                new ReportRow("unknown", null, null, 4L, BigDecimal.ZERO, BigDecimal.ZERO)));

        sut.members(FROM, TO).as(StepVerifier::create)
            .assertNext(report -> {
                assertThat(report.members()).extracting(Member::member).containsExactly("Anna", "Jan", "Ola");
                assertThat(report.members().getFirst().appliances()).containsExactly(
                    new ApplianceCount(131, "Washing machine", 3, 2.5, 0.0, 0.833),
                    new ApplianceCount(132, "Dryer", 0, 0.0, 0.0, null),
                    //a device no longer watched, met in the cycles: listed without its name
                    new ApplianceCount(133, null, 0, 0.0, 0.0, null));
                assertThat(report.members().get(1).total()).isEqualTo(new Count(4, 1.5, 1.0, 0.375));
                //nothing done: listed with zeros
                assertThat(report.members().get(2).total()).isEqualTo(new Count(0, 0.0, 0.0, null));
                assertThat(report.former()).extracting(Member::member).containsExactly("Piotr");
                assertThat(report.former().getFirst().appliances())
                    .containsExactly(new ApplianceCount(132, "Dryer", 2, 1.0, 0.0, 0.5));
                assertThat(report.unattributed()).containsExactly(new Unattributed(131, "Washing machine", 1));
                assertThat(report.unknownPresence()).isEqualTo(4);
                assertThat(report.from()).isEqualTo(FROM);
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_range_that_breaks_its_rules_without_reading_anything() {
        sut.members(TO, FROM).as(StepVerifier::create).verifyErrorSatisfies(ReportServiceTest::invalidRange);
        sut.members(FROM, FROM.plusDays(366).plusSeconds(1)).as(StepVerifier::create)
            .verifyErrorSatisfies(ReportServiceTest::invalidRange);

        verifyNoInteractions(repository, members);
    }

    @Test
    void should_take_a_range_of_366_days() {
        when(repository.report(any(), any())).thenReturn(Flux.empty());

        sut.members(FROM, FROM.plusDays(366)).as(StepVerifier::create)
            .assertNext(report -> assertThat(report.members()).hasSize(3))
            .verifyComplete();
    }

    @Test
    void should_answer_503_when_the_members_cannot_be_read() {
        when(repository.report(any(), any())).thenReturn(Flux.empty());
        when(members.names()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        sut.members(FROM, TO).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
    }

    private static void invalidRange(final Throwable e) {
        assertThat(e).isInstanceOfSatisfying(ApplianceException.class, refused -> {
            assertThat(refused.getError()).isEqualTo(ApplianceError.INVALID_RANGE);
            assertThat(refused.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    private static ReportRow member(final long device, final String name, final long opportunities, final String done,
                                    final String doneAway) {
        return new ReportRow("member", device, name, opportunities, new BigDecimal(done), new BigDecimal(doneAway));
    }

    private static ApplianceStatus watched(final long id, final String name) {
        return new ApplianceStatus(new Appliance(id, name, RoomName.KITCHEN, "192.0.2.1", 0, 1, THRESHOLDS, REMINDERS),
            new CycleView(ApplianceState.IDLE, null, null, null), null, null, null);
    }
}
