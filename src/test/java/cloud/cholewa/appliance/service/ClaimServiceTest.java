package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.api.model.ClaimReply;
import cloud.cholewa.appliance.api.model.ClaimRequest;
import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.CycleView;
import cloud.cholewa.appliance.database.model.ApplianceClaimEntity;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceClaimRepository;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceTagRepository;
import cloud.cholewa.appliance.error.ApplianceError;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.nfc.NfcProperties;
import cloud.cholewa.appliance.nfc.SunVerifier;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.registry.HouseholdProfilesClient;
import cloud.cholewa.appliance.registry.RegistryCallException;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.home.model.HouseholdProfile;
import cloud.cholewa.home.model.MemberRole;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The tag read is the encrypted example of AN12196 under the key a tag leaves the factory with (SunVerifierTest): UID
//04DE5F1EACC040, counter 61; the house is in
//Europe/Warsaw, 08:00Z is 10:00 in October
class ClaimServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final String UID = "04DE5F1EACC040";
    private static final String PICC_DATA = "EF963FF7828658A599F3041510671E88";
    private static final String CMAC = "94EED9EE65337086";
    private static final CycleThresholds THRESHOLDS = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));
    private static final ReminderSettings REMINDERS = new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0));
    private static final Appliance DISHWASHER = new Appliance(51, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1, THRESHOLDS, REMINDERS);

    private final ApplianceMonitor monitor = mock(ApplianceMonitor.class);
    private final ApplianceTagRepository tags = mock(ApplianceTagRepository.class);
    private final ApplianceRunRepository runs = mock(ApplianceRunRepository.class);
    private final ApplianceClaimRepository claims = mock(ApplianceClaimRepository.class);
    private final HouseholdProfilesClient profiles = mock(HouseholdProfilesClient.class);
    private final ClaimService sut = new ClaimService(
        new SunVerifier(new byte[16]),
        new NfcProperties("00000000000000000000000000000000", Map.of(UID.toLowerCase(), 51L)),
        new ApplianceProperties(Duration.ofDays(14), Duration.ofHours(12)),
        monitor, tags, runs, claims, profiles,
        Clock.fixed(T0, ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"), profile("Jan"))));
        when(tags.advance(anyString(), anyLong(), anyInt(), any())).thenReturn(Mono.just(1));
        when(monitor.acknowledge(anyLong(), anyString())).thenReturn(Mono.empty());
        when(claims.save(any())).thenAnswer(call -> {
            final ApplianceClaimEntity claim = call.getArgument(0);
            return Mono.just(ApplianceClaimEntity.builder().id(900L).runId(claim.runId()).member(claim.member())
                .source(claim.source()).claimedAt(claim.claimedAt()).counter(claim.counter()).build());
        });
    }

    //the cycle waiting is acknowledged by the path of the application, then the claim attaches to it
    @Test
    void should_acknowledge_the_finished_cycle_and_store_the_claim_of_the_member() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHED)), Optional.of(status(ApplianceState.IDLE)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51)).thenReturn(Mono.just(finished(77, Duration.ofMinutes(20))));

        sut.claim(request("anna")).as(StepVerifier::create)
            .expectNext(new ClaimReply(51, "Dishwasher", ApplianceState.IDLE, "Anna", 77, LocalDateTime.of(2026, 10, 24, 10, 0)))
            .verifyComplete();

        verify(tags).advance(UID, 51, 61, T0);
        verify(monitor).acknowledge(51, "Anna");
        verify(claims).save(ApplianceClaimEntity.builder().runId(77).member("Anna").source("nfc").claimedAt(T0).counter(61).build());
    }

    //HAS-348: emptied before the end was certain - the acknowledgement of a finishing cycle writes its end
    @Test
    void should_acknowledge_a_cycle_still_finishing() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHING)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51)).thenReturn(Mono.just(finished(77, Duration.ofMinutes(1))));

        sut.claim(request("Jan")).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(monitor).acknowledge(51, "Jan");
    }

    //acknowledged before (the mute of the administrator, or the claim of another member): the claim attaches, no message
    @ParameterizedTest
    @EnumSource(value = ApplianceState.class, names = {"IDLE", "RUNNING"})
    void should_attach_to_a_cycle_finished_within_the_window_without_acknowledging_it(final ApplianceState state) {
        when(monitor.status(51)).thenReturn(Optional.of(status(state)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51)).thenReturn(Mono.just(finished(77, Duration.ofHours(12))));

        sut.claim(request("Anna")).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.runId()).isEqualTo(77))
            .verifyComplete();

        verify(monitor, never()).acknowledge(anyLong(), anyString());
    }

    //acknowledged by somebody else between the read of the state and the acknowledgement
    @Test
    void should_attach_when_the_cycle_was_acknowledged_meanwhile() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHED)));
        when(monitor.acknowledge(51, "Anna")).thenReturn(Mono.error(ApplianceException.nothingToAcknowledge(51)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51)).thenReturn(Mono.just(finished(77, Duration.ofMinutes(2))));

        sut.claim(request("Anna")).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_refuse_a_cycle_that_finished_before_the_window() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.IDLE)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51))
            .thenReturn(Mono.just(finished(77, Duration.ofHours(12).plusSeconds(1))));

        sut.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.NOTHING_TO_CLAIM));

        verify(claims, never()).save(any());
    }

    //nothing to claim, and the read is spent all the same: the same URL is a replay later
    @Test
    void should_refuse_an_appliance_that_never_finished_a_cycle_after_taking_the_counter() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.IDLE)));
        when(runs.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(51)).thenReturn(Mono.empty());

        sut.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.NOTHING_TO_CLAIM));

        verify(tags).advance(UID, 51, 61, T0);
    }

    @Test
    void should_refuse_a_replayed_read() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHED)));
        when(tags.advance(UID, 51, 61, T0)).thenReturn(Mono.just(0));

        sut.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.REPLAYED_TAG));

        verify(monitor, never()).acknowledge(anyLong(), anyString());
        verify(claims, never()).save(any());
    }

    //a genuine read under the key of the tags, but its UID is on no appliance
    @Test
    void should_refuse_a_tag_that_is_not_on_an_appliance() {
        final ClaimService withoutTheTag = new ClaimService(
            new SunVerifier(new byte[16]),
            new NfcProperties("00000000000000000000000000000000", Map.of("04DE5F1EACC041", 51L)),
            new ApplianceProperties(Duration.ofDays(14), Duration.ofHours(12)),
            monitor, tags, runs, claims, profiles,
            Clock.fixed(T0, ZoneId.of("Europe/Warsaw")));

        withoutTheTag.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.INVALID_TAG));

        verifyNoInteractions(tags, profiles);
    }

    @Test
    void should_refuse_a_mac_the_key_does_not_give() {
        sut.claim(new ClaimRequest(PICC_DATA, "94EED9EE65337087", "Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.INVALID_TAG));

        verifyNoInteractions(tags, profiles);
    }

    @Test
    void should_refuse_a_tag_of_an_appliance_it_does_not_watch() {
        when(monitor.status(51)).thenReturn(Optional.empty());

        sut.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.NOT_FOUND_APPLIANCE));

        verifyNoInteractions(tags);
    }

    //the read is not spent: the member can post the same URL again from another profile
    @Test
    void should_refuse_an_unknown_member_without_taking_the_counter() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHED)));

        sut.claim(request("Zofia")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertRefused(e, ApplianceError.UNKNOWN_MEMBER));

        verifyNoInteractions(tags);
    }

    //an unchecked name never goes into a claim; the read is not spent, so it can be posted again
    @Test
    void should_answer_503_when_the_household_registry_cannot_be_read() {
        when(monitor.status(51)).thenReturn(Optional.of(status(ApplianceState.FINISHED)));
        when(profiles.getProfiles()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        sut.claim(request("Anna")).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));

        verifyNoInteractions(tags);
    }

    private static ClaimRequest request(final String member) {
        return new ClaimRequest(PICC_DATA, CMAC, member);
    }

    private static ApplianceRunEntity finished(final long id, final Duration ago) {
        return ApplianceRunEntity.builder().id(id).deviceId(51).startedAt(T0.minus(ago).minusSeconds(3600))
            .finishedAt(T0.minus(ago)).build();
    }

    private static ApplianceStatus status(final ApplianceState state) {
        return new ApplianceStatus(DISHWASHER, new CycleView(state, null, null, null), null, null, null);
    }

    private static HouseholdProfile profile(final String name) {
        return HouseholdProfile.builder().name(name).role(MemberRole.RESIDENT).build();
    }

    private static void assertRefused(final Throwable e, final ApplianceError error) {
        assertThat(e).isInstanceOfSatisfying(ApplianceException.class, refused -> assertThat(refused.getError()).isEqualTo(error));
    }
}
