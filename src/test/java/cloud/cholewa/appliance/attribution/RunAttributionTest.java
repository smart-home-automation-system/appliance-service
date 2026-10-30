package cloud.cholewa.appliance.attribution;

import cloud.cholewa.appliance.attribution.AttributionRule.Source;
import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.presence.PresenceClient;
import cloud.cholewa.appliance.presence.PresenceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The attribution as appliance_run is written with it - its own columns, conditional on what it was computed from -
//with the presence of both moments; the repository and presence-service are mocks
class RunAttributionTest {

    private static final Instant T0 = Instant.parse("2026-10-30T16:32:35Z");
    private static final ApplianceRunEntity FINISHED = ApplianceRunEntity.builder().id(55L).deviceId(131)
        .startedAt(T0.minus(Duration.ofHours(2))).finishedAt(T0.minus(Duration.ofHours(1))).build();
    private static final String[] AT_HOME = {"Krzysztof", "Tobiasz"};

    private final ApplianceRunRepository runs = mock(ApplianceRunRepository.class);
    private final PresenceClient presence = mock(PresenceClient.class);
    private final RunAttribution sut = new RunAttribution(runs, presence,
        new ApplianceProperties(Duration.ofDays(14), Duration.ofHours(12), Duration.ofMinutes(10)),
        Clock.fixed(T0, ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(runs.complete(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.just(1));
        when(runs.presentAtFinish(anyLong(), any())).thenReturn(Mono.just(1));
        when(presence.snapshot()).thenReturn(Mono.just(PresenceSnapshot.of(List.of("Krzysztof", "Tobiasz"))));
    }

    @Test
    void should_store_the_first_confirmation_with_who_was_at_home() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED));

        sut.confirmed(55, "Tobiasz", Source.NFC).as(StepVerifier::create).verifyComplete();

        verify(runs).complete(55, T0, "nfc", new String[]{"Tobiasz"}, "single", AT_HOME, null);
    }

    @Test
    void should_share_with_a_second_member_without_asking_who_is_at_home_again() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED.toBuilder().completedAt(T0.minusSeconds(120))
            .completionSource("app").attributedTo(List.of("Anna")).attribution("single").build()));

        sut.confirmed(55, "Jan", Source.NFC).as(StepVerifier::create).verifyComplete();

        verify(runs).complete(55, T0.minusSeconds(120), "app", new String[]{"Anna", "Jan"}, "shared", null, "single");
        verify(presence, never()).snapshot();
    }

    @Test
    void should_change_nothing_for_the_same_member_again() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED.toBuilder().completedAt(T0.minusSeconds(60))
            .completionSource("app").attributedTo(List.of("Tobiasz")).attribution("single").build()));

        sut.confirmed(55, "Tobiasz", Source.NFC).as(StepVerifier::create).verifyComplete();

        verify(runs, never()).complete(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_complete_a_muted_cycle_unattributed() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED));

        sut.muted(55).as(StepVerifier::create).verifyComplete();

        verify(runs).complete(55, T0, "mute", new String[0], "unattributed", AT_HOME, null);
    }

    //a presence-service that cannot be asked: written as not known (null), never as nobody - and the cycle completed
    @Test
    void should_store_an_unknown_presence_as_not_known() {
        when(presence.snapshot()).thenReturn(Mono.just(PresenceSnapshot.unknown()));
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED));

        sut.confirmed(55, "Tobiasz", Source.APP).as(StepVerifier::create).verifyComplete();

        verify(runs).complete(55, T0, "app", new String[]{"Tobiasz"}, "single", null, null);
    }

    @Test
    void should_take_the_presence_at_the_finish_and_skip_one_not_known() {
        sut.finished(55).as(StepVerifier::create).verifyComplete();

        verify(runs).presentAtFinish(55, AT_HOME);

        when(presence.snapshot()).thenReturn(Mono.just(PresenceSnapshot.unknown()));
        sut.finished(56).as(StepVerifier::create).verifyComplete();

        verify(runs, never()).presentAtFinish(org.mockito.ArgumentMatchers.eq(56L), any());
    }

    //nothing here fails its caller: the claim or the acknowledgement goes on
    @Test
    void should_never_fail_the_caller() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED));
        when(runs.complete(anyLong(), any(), any(), any(), any(), any(), isNull()))
            .thenReturn(Mono.error(new IllegalStateException("database away")));
        when(runs.presentAtFinish(anyLong(), any())).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.confirmed(55, "Tobiasz", Source.NFC).as(StepVerifier::create).verifyComplete();
        sut.finished(55).as(StepVerifier::create).verifyComplete();
    }

    //a row completed in a way this version cannot read is never completed anew
    @Test
    void should_leave_a_completion_it_cannot_read() {
        when(runs.findById(55L)).thenReturn(Mono.just(FINISHED.toBuilder().completedAt(T0.minusSeconds(60))
            .completionSource("door").attribution("single").attributedTo(List.of("Anna")).build()));

        sut.confirmed(55, "Jan", Source.NFC).as(StepVerifier::create).verifyComplete();

        verify(runs, never()).complete(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
