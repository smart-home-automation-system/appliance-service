package cloud.cholewa.appliance.cycle;

import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final Instant END = T0.plus(Duration.ofHours(2));
    private static final CycleThresholds WASHER = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));

    private final ApplianceRunRepository repository = mock(ApplianceRunRepository.class);
    private final RunStore sut = new RunStore(repository);

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(call -> {
            final ApplianceRunEntity run = call.getArgument(0);
            return Mono.just(run.id() == null ? run.toBuilder().id(9L).build() : run);
        });
    }

    @Test
    void should_restore_idle_without_a_run() {
        when(repository.findFirstByDeviceIdOrderByStartedAtDesc(7)).thenReturn(Mono.empty());

        sut.restore(7, WASHER).as(StepVerifier::create)
            .assertNext(restored -> {
                assertThat(restored.detector().view().state()).isEqualTo(ApplianceState.IDLE);
                assertThat(restored.runId()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_restore_a_run_without_an_end_as_running() {
        final RunStore.Restored restored = RunStore.restored(run(null, null), WASHER);

        assertThat(restored.detector().view()).isEqualTo(new CycleView(ApplianceState.RUNNING, T0, null, null));
        assertThat(restored.runId()).isEqualTo(5L);
    }

    @Test
    void should_restore_an_unacknowledged_finished_run_as_waiting_since_it_was_certain() {
        final RunStore.Restored restored = RunStore.restored(run(END, null), WASHER);

        assertThat(restored.detector().view())
            .isEqualTo(new CycleView(ApplianceState.FINISHED, T0, END, END.plus(Duration.ofMinutes(5))));
    }

    @Test
    void should_restore_an_acknowledged_run_as_idle() {
        final RunStore.Restored restored = RunStore.restored(run(END, END.plusSeconds(600)), WASHER);

        assertThat(restored.detector().view().state()).isEqualTo(ApplianceState.IDLE);
        assertThat(restored.runId()).isNull();
    }

    @Test
    void should_insert_a_row_when_a_cycle_starts() {
        sut.write(7, null, new Transition.Started(T0, false)).as(StepVerifier::create).expectNext(9L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(ApplianceRunEntity.builder().deviceId(7).startedAt(T0).build());
    }

    @Test
    void should_complete_the_row_of_the_cycle_when_it_finishes() {
        when(repository.findById(5L)).thenReturn(Mono.just(run(null, null)));

        sut.write(7, 5L, new Transition.Finished(T0, END, 812, 2140)).as(StepVerifier::create).expectNext(5L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(run(null, null).toBuilder().finishedAt(END).energyWh(812L).maxWatts(2140).build());
    }

    //timestamptz keeps microseconds: the row read back is still the row of a start with a finer fraction
    @Test
    void should_complete_the_row_of_a_start_stored_at_microsecond_precision() {
        final Instant start = T0.plusNanos(564_045_500);
        when(repository.findById(5L)).thenReturn(Mono.just(run(null, null).toBuilder().startedAt(T0.plusNanos(564_046_000)).build()));

        sut.write(7, 5L, new Transition.Finished(start.plusNanos(500), END, 812, 2140)).as(StepVerifier::create)
            .expectNext(5L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(5L);
    }

    //the start could not be stored, or the row is of another cycle: the finish inserts the whole row
    @Test
    void should_insert_the_whole_row_of_a_finish_without_the_row_of_its_start() {
        sut.write(7, null, new Transition.Finished(T0, END, 812, 2140)).as(StepVerifier::create).expectNext(9L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(ApplianceRunEntity.builder()
            .deviceId(7).startedAt(T0).finishedAt(END).energyWh(812L).maxWatts(2140).build());
    }

    @Test
    void should_delete_the_row_of_a_run_below_the_minimum() {
        when(repository.deleteById(5L)).thenReturn(Mono.empty());

        sut.write(7, 5L, new Transition.Discarded(T0)).as(StepVerifier::create).verifyComplete();

        verify(repository).deleteById(5L);
    }

    //a failed write is a WARN; the row stays what it was for the next transition
    @Test
    void should_keep_the_row_when_a_write_fails() {
        when(repository.findById(5L)).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.write(7, 5L, new Transition.Finished(T0, END, 812, 2140)).as(StepVerifier::create).expectNext(5L).verifyComplete();
        verify(repository, never()).save(any());
    }

    private static ApplianceRunEntity run(final Instant finishedAt, final Instant acknowledgedAt) {
        return ApplianceRunEntity.builder().id(5L).deviceId(7).startedAt(T0).finishedAt(finishedAt)
            .acknowledgedAt(acknowledgedAt).build();
    }
}
