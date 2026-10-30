package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.attribution.AttributionRule.Source;
import cloud.cholewa.appliance.attribution.RunAttribution;
import cloud.cholewa.appliance.database.model.ApplianceClaimEntity;
import cloud.cholewa.appliance.database.repository.ApplianceClaimRepository;
import cloud.cholewa.appliance.api.model.ApplianceReply;
import cloud.cholewa.appliance.api.model.RunReply;
import cloud.cholewa.appliance.api.model.SampleReply;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.error.ApplianceError;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.registry.HouseholdProfilesClient;
import cloud.cholewa.appliance.registry.RegistryCallException;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.home.model.HouseholdProfile;
import cloud.cholewa.home.model.MemberRole;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The house is in Europe/Warsaw: 08:00Z is 10:00 in October
class ApplianceServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final CycleThresholds THRESHOLDS = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));
    private static final ReminderSettings REMINDERS = new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0));
    private static final Appliance DISHWASHER = new Appliance(51, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1, THRESHOLDS, REMINDERS);

    private final ApplianceMonitor monitor = mock(ApplianceMonitor.class);
    private final ApplianceRunRepository runs = mock(ApplianceRunRepository.class);
    private final ApplianceSampleRepository samples = mock(ApplianceSampleRepository.class);
    private final HouseholdProfilesClient profiles = mock(HouseholdProfilesClient.class);
    private final ApplianceClaimRepository claims = mock(ApplianceClaimRepository.class);
    private final RunAttribution attribution = mock(RunAttribution.class);
    private final ApplianceService sut = new ApplianceService(monitor, runs, claims, attribution, samples, profiles,
        Clock.fixed(T0, ZoneId.of("Europe/Warsaw")));

    @Test
    void should_answer_an_appliance_by_the_clock_of_the_house() {
        when(monitor.statuses()).thenReturn(List.of(new ApplianceStatus(DISHWASHER,
            new CycleView(ApplianceState.FINISHED, T0, T0.plusSeconds(3600), T0.plusSeconds(3900)), 0.4, T0.plusSeconds(3920), true)));

        sut.appliances().as(StepVerifier::create)
            .expectNext(List.of(new ApplianceReply(51, "Dishwasher", RoomName.KITCHEN, ApplianceState.FINISHED, 0.4,
                LocalDateTime.of(2026, 10, 24, 11, 5, 20), LocalDateTime.of(2026, 10, 24, 10, 0),
                LocalDateTime.of(2026, 10, 24, 11, 0), LocalDateTime.of(2026, 10, 24, 11, 5), true)))
            .verifyComplete();
    }

    @Test
    void should_leave_out_what_is_not_known() {
        when(monitor.status(51)).thenReturn(Optional.of(idle()));

        sut.appliance(51).as(StepVerifier::create)
            .expectNext(new ApplianceReply(51, "Dishwasher", RoomName.KITCHEN, ApplianceState.IDLE,
                null, null, null, null, null, null))
            .verifyComplete();
    }

    @Test
    void should_refuse_an_appliance_it_does_not_watch() {
        when(monitor.status(anyLong())).thenReturn(Optional.empty());

        sut.appliance(99).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ApplianceException.class,
                refused -> assertThat(refused.getError()).isEqualTo(ApplianceError.NOT_FOUND_APPLIANCE)))
            .verify();
        sut.runs(99, LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0)).as(StepVerifier::create)
            .expectError(ApplianceException.class)
            .verify();
        verifyNoInteractions(runs, samples);
    }

    @Test
    void should_read_the_runs_of_a_range_of_the_house_clock() {
        when(monitor.status(51)).thenReturn(Optional.of(idle()));
        when(runs.findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(
            51, Instant.parse("2026-09-30T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z")))
            .thenReturn(Flux.just(ApplianceRunEntity.builder().id(3L).deviceId(51).startedAt(T0)
                .finishedAt(T0.plusSeconds(7200)).energyWh(812L).maxWatts(2140).build()));

        sut.runs(51, LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0)).as(StepVerifier::create)
            .expectNext(new RunReply(3, LocalDateTime.of(2026, 10, 24, 10, 0), LocalDateTime.of(2026, 10, 24, 12, 0),
                812L, 2140, null, null, null, null, null, null, null, null))
            .verifyComplete();
    }

    @Test
    void should_read_the_samples_of_a_range() {
        when(monitor.status(51)).thenReturn(Optional.of(idle()));
        when(samples.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(
            51, Instant.parse("2026-10-23T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z")))
            .thenReturn(Flux.just(new ApplianceSampleEntity(1L, 51, T0, new BigDecimal("511.69"))));

        sut.samples(51, LocalDateTime.of(2026, 10, 24, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0)).as(StepVerifier::create)
            .expectNext(new SampleReply(LocalDateTime.of(2026, 10, 24, 10, 0), new BigDecimal("511.69")))
            .verifyComplete();
    }

    //the span on the wall clock: two days across the night the clocks go back (49 hours) are two days
    @Test
    void should_count_the_span_of_a_range_on_the_wall_clock() {
        when(monitor.status(51)).thenReturn(Optional.of(idle()));
        when(samples.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(
            51, Instant.parse("2026-10-24T22:00:00Z"), Instant.parse("2026-10-26T23:00:00Z")))
            .thenReturn(Flux.empty());

        sut.samples(51, LocalDateTime.of(2026, 10, 25, 0, 0), LocalDateTime.of(2026, 10, 27, 0, 0)).as(StepVerifier::create)
            .verifyComplete();
    }

    //HAS-347: the member is checked against the profiles whatever the case, and goes on by the name of the registry
    @Test
    void should_acknowledge_by_the_name_the_registry_has() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"), profile("Jan"))));
        when(monitor.acknowledge(51, "Anna")).thenReturn(Mono.just(9L));
        when(claims.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(attribution.confirmed(9, "Anna", Source.APP)).thenReturn(Mono.empty());

        sut.acknowledge(51, " anna ").as(StepVerifier::create).verifyComplete();

        verify(monitor).acknowledge(51, "Anna");
        //HAS-344 (owner 2026-10-30): with a member it is a confirmation equal to a tag read
        verify(claims).save(ApplianceClaimEntity.builder().runId(9).member("Anna").source("app").claimedAt(T0).build());
        verify(attribution).confirmed(9, "Anna", Source.APP);
        verify(attribution, never()).muted(anyLong());
    }

    //a confirmation that could not be stored never fails the acknowledgement, and the cycle is still attributed
    @Test
    void should_attribute_the_cycle_when_the_confirmation_cannot_be_stored() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))));
        when(monitor.acknowledge(51, "Anna")).thenReturn(Mono.just(9L));
        when(claims.save(any())).thenReturn(Mono.error(new IllegalStateException("database away")));
        when(attribution.confirmed(9, "Anna", Source.APP)).thenReturn(Mono.empty());

        sut.acknowledge(51, "Anna").as(StepVerifier::create).verifyComplete();

        verify(attribution).confirmed(9, "Anna", Source.APP);
    }

    //a name the registry does not have: 400, and nothing is acknowledged
    @Test
    void should_refuse_an_unknown_member_and_acknowledge_nothing() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))));

        sut.acknowledge(51, "Nobody").as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ApplianceException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(ApplianceError.UNKNOWN_MEMBER);
                assertThat(refused.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            }))
            .verify();
        verify(monitor, never()).acknowledge(anyLong(), any());
    }

    //a failed read of the profiles never holds the acknowledgement back: it goes without a name
    @Test
    void should_acknowledge_without_a_name_when_the_profiles_cannot_be_read() {
        when(profiles.getProfiles()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));
        when(monitor.acknowledge(51, null)).thenReturn(Mono.just(9L));
        when(attribution.muted(9)).thenReturn(Mono.empty());

        sut.acknowledge(51, "Anna").as(StepVerifier::create).verifyComplete();

        verify(monitor).acknowledge(51, null);
        //without a checked name it is a mute: unattributed, no claim
        verify(attribution).muted(9);
        verifyNoInteractions(claims);
    }

    //nobody named (Bruno): no read of the profiles
    @Test
    void should_acknowledge_without_reading_the_profiles_when_nobody_is_named() {
        when(monitor.acknowledge(51, null)).thenReturn(Mono.just(9L));
        when(attribution.muted(9)).thenReturn(Mono.empty());

        sut.acknowledge(51, null).as(StepVerifier::create).verifyComplete();
        sut.acknowledge(51, "    ").as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @CsvSource({
        "runs, 2026-10-02T00:00:00, 2026-10-01T00:00:00, to must be after from",
        "runs, 2026-10-01T00:00:00, 2026-10-01T00:00:00, to must be after from",
        "runs, 2026-09-01T00:00:00, 2026-10-02T00:00:01, from and to must be at most 31 days apart",
        "samples, 2026-10-01T00:00:00, 2026-10-03T00:00:01, from and to must be at most 2 days apart"
    })
    void should_refuse_a_range_that_breaks_a_rule(final String what, final LocalDateTime from, final LocalDateTime to,
                                                  final String reason) {
        when(monitor.status(51)).thenReturn(Optional.of(idle()));

        final Flux<?> answer = what.equals("runs") ? sut.runs(51, from, to) : sut.samples(51, from, to);

        answer.as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getReason()).isEqualTo(reason);
            }))
            .verify();
        verifyNoInteractions(runs, samples);
    }

    private static HouseholdProfile profile(final String name) {
        return HouseholdProfile.builder().name(name).role(MemberRole.RESIDENT).build();
    }

    private static ApplianceStatus idle() {
        return new ApplianceStatus(DISHWASHER, new CycleView(ApplianceState.IDLE, null, null, null), null, null, null);
    }
}
