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
import static org.mockito.Mockito.times;
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

    //HAS-279: a finished cycle nobody acknowledged, ended by a new one, is acknowledged when the new one started
    @Test
    void should_close_a_replaced_cycle_before_storing_the_new_one() {
        final Instant second = END.plus(Duration.ofHours(1));
        when(repository.findById(5L)).thenReturn(Mono.just(run(END, null)));

        sut.write(7, 5L, new Transition.Started(second, true)).as(StepVerifier::create).expectNext(9L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0)).isEqualTo(run(END, second));
        assertThat(saved.getAllValues().get(1)).isEqualTo(ApplianceRunEntity.builder().deviceId(7).startedAt(second).build());
    }

    @Test
    void should_store_the_new_cycle_when_the_replaced_one_cannot_be_closed() {
        when(repository.findById(5L)).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.write(7, 5L, new Transition.Started(END.plusSeconds(60), true)).as(StepVerifier::create).expectNext(9L).verifyComplete();
    }

    @Test
    void should_write_the_messages_of_a_cycle() {
        when(repository.findById(5L)).thenReturn(Mono.just(run(END, null)));

        sut.notified(5L, END.plusSeconds(1)).as(StepVerifier::create).verifyComplete();
        sut.reminded(5L, 2, END.plusSeconds(3600)).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).notifiedAt()).isEqualTo(END.plusSeconds(1));
        assertThat(saved.getAllValues().get(0).lastMessageAt()).isEqualTo(END.plusSeconds(1));
        assertThat(saved.getAllValues().get(1).remindersSent()).isEqualTo(2);
        assertThat(saved.getAllValues().get(1).lastMessageAt()).isEqualTo(END.plusSeconds(3600));
    }

    //acknowledged twice keeps the first time
    @Test
    void should_keep_the_first_acknowledgement() {
        when(repository.findById(5L)).thenReturn(Mono.just(run(END, END.plusSeconds(60))));

        sut.acknowledge(5L, END.plusSeconds(600)).as(StepVerifier::create)
            .assertNext(run -> assertThat(run.acknowledgedAt()).isEqualTo(END.plusSeconds(60)))
            .verifyComplete();
        verify(repository, never()).save(any());
    }

    //HAS-347: who acknowledged it, stored with the time; the first acknowledgement keeps its member
    @Test
    void should_store_who_acknowledged_it() {
        when(repository.findById(5L)).thenReturn(Mono.just(run(END, null)));

        sut.acknowledge(5L, END.plusSeconds(600), "Anna").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().acknowledgedAt()).isEqualTo(END.plusSeconds(600));
        assertThat(saved.getValue().acknowledgedBy()).isEqualTo("Anna");
    }

    //HAS-348: the end and the acknowledgement of a cycle emptied while finishing, in one write
    @Test
    void should_finish_and_acknowledge_a_cycle_in_one_write() {
        when(repository.findById(5L)).thenReturn(Mono.just(run(null, null)));

        sut.finishAcknowledged(7, 5L, new Transition.Finished(T0, END, 640L, 2100), END.plusSeconds(90), "Anna")
            .as(StepVerifier::create).expectNext(5L).verifyComplete();

        final ArgumentCaptor<ApplianceRunEntity> saved = ArgumentCaptor.forClass(ApplianceRunEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).satisfies(run -> {
            assertThat(run.finishedAt()).isEqualTo(END);
            assertThat(run.energyWh()).isEqualTo(640L);
            assertThat(run.maxWatts()).isEqualTo(2100);
            assertThat(run.acknowledgedAt()).isEqualTo(END.plusSeconds(90));
            assertThat(run.acknowledgedBy()).isEqualTo("Anna");
            assertThat(run.notifiedAt()).isNull();
        });
    }

    //the caller decides what a failed write means - here it is not swallowed
    @Test
    void should_hand_a_failed_early_acknowledgement_to_the_caller() {
        when(repository.findById(5L)).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.finishAcknowledged(7, 5L, new Transition.Finished(T0, END, 640L, 2100), END, null)
            .as(StepVerifier::create).verifyError(IllegalStateException.class);
    }

    private static ApplianceRunEntity run(final Instant finishedAt, final Instant acknowledgedAt) {
        return ApplianceRunEntity.builder().id(5L).deviceId(7).startedAt(T0).finishedAt(finishedAt)
            .acknowledgedAt(acknowledgedAt).build();
    }
}
