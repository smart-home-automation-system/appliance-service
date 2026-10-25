package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.cycle.CycleDetector;
import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.cycle.RunStore;
import cloud.cholewa.appliance.cycle.Transition;
import cloud.cholewa.appliance.database.model.ApplianceSampleEntity;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.power.PowerReader;
import cloud.cholewa.appliance.power.PowerSample;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.appliance.registry.ApplianceRegistry;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplianceMonitorTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final CycleThresholds DRYER = new CycleThresholds(50, 5, Duration.ofMinutes(15), Duration.ofMinutes(3));
    private static final Appliance DRYER_PLUG = new Appliance(31, "Dryer", RoomName.BOILER, "192.0.2.31", 0, 1, DRYER);
    private static final Appliance WASHER_PLUG = new Appliance(32, "Washing machine", RoomName.BOILER, "192.0.2.32", 0, 1, DRYER);

    private final ApplianceRegistry registry = mock(ApplianceRegistry.class);
    private final RunStore runStore = mock(RunStore.class);
    private final PowerReader reader = mock(PowerReader.class);
    private final ApplianceSampleRepository samples = mock(ApplianceSampleRepository.class);
    private final ApplianceMonitor sut = new ApplianceMonitor(registry, runStore, reader, samples);

    @BeforeEach
    void setUp() {
        when(samples.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
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
        final Appliance moved = new Appliance(32, "Washer", RoomName.BATHROOM_DOWN, "192.0.2.33", 0, 1, DRYER);
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
        assertThat(sut.status(31)).get().extracting(status -> status.cycle().state()).isEqualTo(ApplianceState.FINISHED);
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

    private void watch(final Appliance appliance, final CycleDetector detector, final Long runId) {
        when(registry.read()).thenReturn(Mono.just(List.of(appliance)));
        when(runStore.restore(appliance.id(), appliance.thresholds()))
            .thenReturn(Mono.just(new RunStore.Restored(detector, runId)));
        sut.refresh().as(StepVerifier::create).verifyComplete();
    }
}
