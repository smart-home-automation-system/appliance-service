package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.attribution.RunAttribution;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleDetector;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.RunStore;
import cloud.cholewa.appliance.cycle.Transition;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.error.ApplianceError;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.power.PowerReader;
import cloud.cholewa.appliance.power.PowerSample;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.reminder.HouseholdNotifier;
import cloud.cholewa.appliance.reminder.ReminderSettings;
import cloud.cholewa.appliance.reminder.Reminders;
import cloud.cholewa.appliance.registry.ApplianceRegistry;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplianceMonitorTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final Instant FALL = T0.plus(Duration.ofHours(2));
    private static final CycleThresholds DRYER = new CycleThresholds(50, 5, Duration.ofMinutes(15), Duration.ofMinutes(3));
    private static final ReminderSettings REMINDERS = new ReminderSettings(Duration.ofMinutes(30), 4, LocalTime.of(23, 0), LocalTime.of(7, 0));
    private static final Appliance DRYER_PLUG = new Appliance(31, "Dryer", RoomName.BOILER, "192.0.2.31", 0, 1, DRYER, REMINDERS);
    private static final Appliance WASHER_PLUG = new Appliance(32, "Washing machine", RoomName.BOILER, "192.0.2.32", 0, 1, DRYER, REMINDERS);

    private final ApplianceRegistry registry = mock(ApplianceRegistry.class);
    private final RunStore runStore = mock(RunStore.class);
    private final PowerReader reader = mock(PowerReader.class);
    private final ApplianceSampleRepository samples = mock(ApplianceSampleRepository.class);
    private final Reminders reminders = mock(Reminders.class);
    private final HouseholdNotifier notifier = mock(HouseholdNotifier.class);
    private final RunAttribution attribution = mock(RunAttribution.class);
    private final ApplianceMonitor sut = new ApplianceMonitor(registry, runStore, reader, samples, reminders, notifier, attribution,
        Clock.fixed(T0.plus(Duration.ofHours(3)), ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(samples.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(reminders.check(any(), anyLong())).thenReturn(Mono.empty());
        when(attribution.finished(anyLong())).thenReturn(Mono.empty());
    }

    @Test
    void should_watch_the_appliances_of_the_registry_from_their_latest_run() {
        when(registry.read()).thenReturn(Mono.just(List.of(DRYER_PLUG)));
        when(runStore.restore(31, DRYER)).thenReturn(Mono.just(new RunStore.Restored(CycleDetector.running(T0), 5L)));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.statuses()).singleElement().satisfies(status -> {
            assertThat(status.appliance()).isEqualTo(DRYER_PLUG);
            assertThat(status.cycle().state()).isEqualTo(ApplianceState.RUNNING);
            assertThat(status.cycle().runningSince()).isEqualTo(T0);
            // nothing polled since the start: not known
            assertThat(status.watts()).isNull();
            assertThat(status.reachable()).isNull();
        });
    }

    //a cycle that cannot be restored is not replaced by an idle one: the appliance waits for the next refresh
    @Test
    void should_try_again_an_appliance_whose_cycle_could_not_be_restored() {
        when(registry.read()).thenReturn(Mono.just(List.of(DRYER_PLUG)));
        when(runStore.restore(31, DRYER)).thenReturn(Mono.error(new IllegalStateException("database away")),
            Mono.just(new RunStore.Restored(CycleDetector.running(T0), 5L)));

        sut.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sut.status(31)).isEmpty();

        sut.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.RUNNING);
    }

    @Test
    void should_keep_the_appliances_when_the_registry_cannot_be_read() {
        when(registry.read()).thenReturn(Mono.just(List.of(DRYER_PLUG)), Mono.empty());
        when(runStore.restore(anyLong(), any())).thenReturn(Mono.just(new RunStore.Restored(CycleDetector.idle(), null)));

        sut.refresh().as(StepVerifier::create).verifyComplete();
        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.status(31)).isPresent();
    }

    @Test
    void should_forget_an_appliance_the_registry_no_longer_has_and_take_its_new_settings() {
        final Appliance moved = new Appliance(32, "Washer", RoomName.BATHROOM_DOWN, "192.0.2.33", 0, 1, DRYER, REMINDERS);
        when(registry.read()).thenReturn(Mono.just(List.of(DRYER_PLUG, WASHER_PLUG)), Mono.just(List.of(moved)));
        when(runStore.restore(anyLong(), any())).thenReturn(Mono.just(new RunStore.Restored(CycleDetector.idle(), null)));

        sut.refresh().as(StepVerifier::create).verifyComplete();
        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.status(31)).isEmpty();
        assertThat(sut.status(32)).get().extracting(ApplianceStatus::appliance).isEqualTo(moved);
    }

    @Test
    void should_store_a_valid_sample_and_give_it_to_the_detector() {
        watch(DRYER_PLUG, CycleDetector.idle(), null);
        when(reader.read(DRYER_PLUG)).thenReturn(Mono.just(PowerSample.reading(T0, 511.687, 100L)));

        sut.sample().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<ApplianceSampleEntity> stored = ArgumentCaptor.forClass(ApplianceSampleEntity.class);
        verify(samples).save(stored.capture());
        assertThat(stored.getValue().deviceId()).isEqualTo(31);
        assertThat(stored.getValue().at()).isEqualTo(T0);
        assertThat(stored.getValue().watts()).isEqualTo(new BigDecimal("511.69"));
        assertThat(sut.status(31)).get().satisfies(status -> {
            assertThat(status.watts()).isEqualTo(511.687);
            assertThat(status.sampledAt()).isEqualTo(T0);
            assertThat(status.reachable()).isTrue();
        });
    }

    @Test
    void should_write_the_start_of_a_cycle_and_keep_its_row() {
        watch(DRYER_PLUG, CycleDetector.idle(), null);
        when(reader.read(DRYER_PLUG)).thenReturn(
            Mono.just(PowerSample.reading(T0, 2300.0, 100L)),
            Mono.just(PowerSample.reading(T0.plusSeconds(30), 2300.0, 1250L)),
            Mono.just(PowerSample.reading(T0.plusSeconds(60), 2300.0, 2400L)));
        when(runStore.write(31, null, new Transition.Started(T0, false))).thenReturn(Mono.just(9L));

        sut.sample().as(StepVerifier::create).verifyComplete();
        sut.sample().as(StepVerifier::create).verifyComplete();
        sut.sample().as(StepVerifier::create).verifyComplete();

        verify(runStore).write(31, null, new Transition.Started(T0, false));
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.RUNNING);
        //a start is no finish: nobody asks who is at home
        verify(attribution, never()).finished(anyLong());
    }

    @Test
    void should_end_a_cycle_on_the_row_it_was_restored_with() {
        watch(DRYER_PLUG, CycleDetector.running(T0), 5L);
        when(reader.read(DRYER_PLUG)).thenReturn(
            Mono.just(PowerSample.reading(T0.plus(Duration.ofMinutes(40)), 0.4, null)),
            Mono.just(PowerSample.reading(T0.plus(Duration.ofMinutes(43)), 0.4, null)));
        when(runStore.write(eq(31L), eq(5L), any(Transition.Finished.class))).thenReturn(Mono.just(5L));

        sut.sample().as(StepVerifier::create).verifyComplete();
        sut.sample().as(StepVerifier::create).verifyComplete();

        verify(runStore).write(31, 5L, new Transition.Finished(T0, T0.plus(Duration.ofMinutes(40)), 0, 0));
        //announced by the minute check, never by the sampling pass a slow broker would stall
        verify(reminders, never()).check(any(), anyLong());
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.FINISHED);
        //HAS-344: who is at home when it finished
        verify(attribution).finished(5L);
    }

    //an invalid answer or a plug that does not answer: nothing stored, nothing given to the detector
    @Test
    void should_keep_the_state_over_a_sample_that_is_not_valid() {
        watch(DRYER_PLUG, CycleDetector.running(T0), 5L);
        when(reader.read(DRYER_PLUG)).thenReturn(Mono.just(PowerSample.unanswered(T0)), Mono.just(PowerSample.invalid(T0)));

        sut.sample().as(StepVerifier::create).verifyComplete();
        assertThat(sut.status(31)).get().extracting(ApplianceStatus::reachable).isEqualTo(false);
        sut.sample().as(StepVerifier::create).verifyComplete();

        verify(samples, never()).save(any());
        verify(runStore, never()).write(anyLong(), any(), any());
        assertThat(sut.status(31)).get().satisfies(status -> {
            assertThat(status.reachable()).isTrue();
            assertThat(status.watts()).isNull();
            assertThat(status.cycle().state()).isEqualTo(ApplianceState.RUNNING);
        });
    }

    //a failed write of a sample is a WARN: the detector still gets the sample
    @Test
    void should_go_on_when_a_sample_cannot_be_stored() {
        watch(DRYER_PLUG, CycleDetector.running(T0), 5L);
        doReturn(Mono.error(new IllegalStateException("database away"))).when(samples).save(any());
        when(reader.read(DRYER_PLUG)).thenReturn(Mono.just(PowerSample.reading(T0.plusSeconds(600), 0.0, null)));

        sut.sample().as(StepVerifier::create).verifyComplete();

        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.FINISHING);
    }

    //HAS-279: every finished cycle with a row is checked for its reminders, nothing else
    @Test
    void should_check_the_reminders_of_the_finished_cycles_only() {
        when(registry.read()).thenReturn(Mono.just(List.of(DRYER_PLUG, WASHER_PLUG)));
        when(runStore.restore(31, DRYER)).thenReturn(Mono.just(new RunStore.Restored(finished(), 5L)));
        when(runStore.restore(32, DRYER)).thenReturn(Mono.just(new RunStore.Restored(CycleDetector.running(T0), 6L)));
        sut.refresh().as(StepVerifier::create).verifyComplete();

        sut.remind().as(StepVerifier::create).verifyComplete();

        verify(reminders).check(DRYER_PLUG, 5L);
        verify(reminders, never()).check(eq(WASHER_PLUG), anyLong());
    }

    @Test
    void should_acknowledge_a_finished_cycle_once() {
        watch(DRYER_PLUG, finished(), 5L);
        final Instant now = T0.plus(Duration.ofHours(3));
        when(runStore.acknowledge(5L, now, "Anna")).thenReturn(Mono.just(ApplianceRunEntity.builder().id(5L).deviceId(31)
            .startedAt(T0).remindersSent(2).acknowledgedAt(now).build()));
        when(notifier.acknowledged(DRYER_PLUG, now, 2, "Anna")).thenReturn(Mono.just(true));

        sut.acknowledge(31, "Anna").as(StepVerifier::create).expectNext(5L).verifyComplete();

        verify(notifier).acknowledged(DRYER_PLUG, now, 2, "Anna");
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.IDLE);
        sut.acknowledge(31, null).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((ApplianceException) e).getError()).isEqualTo(ApplianceError.NOTHING_TO_ACKNOWLEDGE))
            .verify();
    }

    @Test
    void should_refuse_to_acknowledge_what_does_not_wait() {
        watch(DRYER_PLUG, CycleDetector.running(T0), 5L);

        sut.acknowledge(31, null).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((ApplianceException) e).getError()).isEqualTo(ApplianceError.NOTHING_TO_ACKNOWLEDGE))
            .verify();
        sut.acknowledge(99, null).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((ApplianceException) e).getError()).isEqualTo(ApplianceError.NOT_FOUND_APPLIANCE))
            .verify();
        verify(runStore, never()).acknowledge(anyLong(), any(), any());
    }

    //a row gone from the database: nothing remains to remind, the cycle is acknowledged in memory
    @Test
    void should_acknowledge_a_cycle_whose_row_is_gone() {
        watch(DRYER_PLUG, finished(), 5L);
        when(runStore.acknowledge(eq(5L), any(), isNull())).thenReturn(Mono.empty());
        when(notifier.acknowledged(eq(DRYER_PLUG), any(), eq(0), isNull())).thenReturn(Mono.just(true));

        sut.acknowledge(31, null).as(StepVerifier::create).expectNext(5L).verifyComplete();

        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.IDLE);
        verify(notifier).acknowledged(eq(DRYER_PLUG), any(), eq(0), isNull());
    }

    //the row first: a failed write leaves the cycle waiting and reminded, and nobody is told it was acknowledged
    @Test
    void should_keep_the_cycle_waiting_when_the_acknowledgement_cannot_be_stored() {
        watch(DRYER_PLUG, finished(), 5L);
        when(runStore.acknowledge(eq(5L), any(), isNull())).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.acknowledge(31, null).as(StepVerifier::create).verifyError(IllegalStateException.class);

        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.FINISHED);
        verify(notifier, never()).acknowledged(any(), any(), anyInt(), any());
    }

    //HAS-348: emptied before the end was certain - ended at the fall and acknowledged in one write, never
    //announced: only "appliance acknowledged", with no reminder before it
    @Test
    void should_acknowledge_a_cycle_still_finishing_without_announcing_it() {
        final CycleDetector detector = finishing();
        watch(DRYER_PLUG, detector, 5L);
        final Instant now = T0.plus(Duration.ofHours(3));
        when(runStore.finishAcknowledged(eq(31L), eq(5L), any(), eq(now), eq("Anna"))).thenReturn(Mono.just(5L));
        when(notifier.acknowledged(DRYER_PLUG, now, 0, "Anna")).thenReturn(Mono.just(true));

        sut.acknowledge(31, "Anna").as(StepVerifier::create).expectNext(5L).verifyComplete();

        final ArgumentCaptor<Transition.Finished> finished = ArgumentCaptor.forClass(Transition.Finished.class);
        verify(runStore).finishAcknowledged(eq(31L), eq(5L), finished.capture(), eq(now), eq("Anna"));
        assertThat(finished.getValue().startedAt()).isEqualTo(T0);
        assertThat(finished.getValue().finishedAt()).isEqualTo(FALL);
        verify(notifier).acknowledged(DRYER_PLUG, now, 0, "Anna");
        verify(notifier, never()).finished(any(), any());
        //HAS-344: it finished in this write - who was at home then
        verify(attribution).finished(5L);
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.IDLE);
        sut.remind().as(StepVerifier::create).verifyComplete();
        verify(reminders, never()).check(any(), anyLong());
    }

    //a row that cannot be written: nobody is told it was acknowledged, and the cycle is finishing again
    @Test
    void should_finish_as_usual_when_an_early_acknowledgement_cannot_be_stored() {
        watch(DRYER_PLUG, finishing(), 5L);
        when(runStore.finishAcknowledged(anyLong(), any(), any(), any(), any()))
            .thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.acknowledge(31, null).as(StepVerifier::create).verifyError(IllegalStateException.class);

        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.FINISHING);
        verify(notifier, never()).acknowledged(any(), any(), anyInt(), any());
    }

    //shorter than minRunMinutes (the six minutes of a refresh of the dryer): acknowledged all the same
    @Test
    void should_acknowledge_a_finishing_run_shorter_than_the_minimum() {
        final CycleDetector detector = CycleDetector.running(FALL.minus(Duration.ofMinutes(5)));
        detector.sample(FALL, 1.0, null, DRYER);
        watch(DRYER_PLUG, detector, 5L);
        final Instant now = T0.plus(Duration.ofHours(3));
        when(runStore.finishAcknowledged(eq(31L), eq(5L), any(), eq(now), eq("Anna"))).thenReturn(Mono.just(5L));
        when(notifier.acknowledged(DRYER_PLUG, now, 0, "Anna")).thenReturn(Mono.just(true));

        sut.acknowledge(31, "Anna").as(StepVerifier::create).expectNext(5L).verifyComplete();

        verify(notifier).acknowledged(DRYER_PLUG, now, 0, "Anna");
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.IDLE);
    }

    //a cycle that ran two hours and fell at FALL, its end not certain yet
    private static CycleDetector finishing() {
        final CycleDetector detector = CycleDetector.running(T0);
        detector.sample(FALL, 1.0, null, DRYER);
        return detector;
    }

    private static CycleDetector finished() {
        return CycleDetector.finished(T0, T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofMinutes(125)));
    }

    private void watch(final Appliance appliance, final CycleDetector detector, final Long runId) {
        when(registry.read()).thenReturn(Mono.just(List.of(appliance)));
        when(runStore.restore(appliance.id(), appliance.thresholds()))
            .thenReturn(Mono.just(new RunStore.Restored(detector, runId)));
        sut.refresh().as(StepVerifier::create).verifyComplete();
    }
}
