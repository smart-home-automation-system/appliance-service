package cloud.cholewa.appliance.cycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//The detector without I/O, on samples every 30 s as the poll gives them (DoD of HAS-278)
class CycleDetectorTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
    private static final Duration POLL = Duration.ofSeconds(30);

    //a washing machine: running above 10 W, idle below 3 W, at least 10 min, finished after 5 min below
    private static final CycleThresholds WASHER = new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5));
    //a dryer: running above 50 W, idle below 5 W, at least 15 min, finished after 3 min below
    private static final CycleThresholds DRYER = new CycleThresholds(50, 5, Duration.ofMinutes(15), Duration.ofMinutes(3));

    private final CycleDetector detector = CycleDetector.idle();
    private final List<Transition> transitions = new ArrayList<>();
    private Instant now = T0;
    private long total;

    @Test
    void should_start_a_cycle_after_a_minute_above_the_threshold_at_its_first_sample() {
        run(WASHER, 2, 400.0);
        assertThat(transitions).isEmpty();
        assertThat(detector.view().state()).isEqualTo(ApplianceState.IDLE);

        run(WASHER, 1, 400.0);

        assertThat(transitions).containsExactly(new Transition.Started(T0, false));
        assertThat(detector.view()).isEqualTo(new CycleView(ApplianceState.RUNNING, T0, null, null));
    }

    @Test
    void should_not_start_on_a_draw_that_falls_back_within_the_minute() {
        run(WASHER, 2, 400.0);
        run(WASHER, 1, 6.0);
        run(WASHER, 2, 400.0);

        assertThat(transitions).isEmpty();
        assertThat(detector.view().state()).isEqualTo(ApplianceState.IDLE);
    }

    //the drum stands still between the phases at a few watts: a pause shorter than finishedAfter is no end
    @Test
    void should_carry_a_cycle_over_the_pauses_of_a_washing_machine() {
        run(WASHER, 40, 300.0);
        run(WASHER, 8, 1.5);            // 4 minutes still
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHING);
        run(WASHER, 20, 2100.0);        // heating up again
        assertThat(detector.view().state()).isEqualTo(ApplianceState.RUNNING);
        final Instant fall = now;
        run(WASHER, 11, 0.4);           // 5 minutes off

        assertThat(transitions).hasSize(2).last()
            .isInstanceOfSatisfying(Transition.Finished.class, finished -> {
                assertThat(finished.startedAt()).isEqualTo(T0);
                assertThat(finished.finishedAt()).isEqualTo(fall);
                assertThat(finished.maxWatts()).isEqualTo(2100);
            });
        assertThat(detector.view()).isEqualTo(new CycleView(ApplianceState.FINISHED, T0, fall, now.minus(POLL)));
    }

    //a draw between the two thresholds in FINISHING for a minute ends the pause: anything at or above idleBelowWatts
    @Test
    void should_take_a_draw_between_the_thresholds_as_a_pause() {
        run(WASHER, 30, 300.0);
        run(WASHER, 4, 1.0);
        run(WASHER, 2, 5.0);
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHING);

        run(WASHER, 1, 5.0);

        assertThat(detector.view().state()).isEqualTo(ApplianceState.RUNNING);
    }

    //the dryer of the house on 2026-10-26 (HAS-285): the drying ended at 480 W, then the drum turned for a few
    //seconds about every minute while nobody emptied it - one sample of 75-210 W between samples of 2 W
    @Test
    void should_end_a_dryer_whose_drum_turns_while_it_waits() {
        run(DRYER, 120, 480.0);
        final Instant fall = now;
        for (int i = 0; i < 4; i++) {
            run(DRYER, 1, 2.2);
            run(DRYER, 1, 180.0);
        }

        assertThat(transitions).hasSize(2).last()
            .isInstanceOfSatisfying(Transition.Finished.class, finished -> {
                assertThat(finished.finishedAt()).isEqualTo(fall);
                assertThat(finished.maxWatts()).isEqualTo(480);
            });
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHED);
    }

    //the same turns once the end is announced: shorter than the minute of a start, the cycle keeps waiting
    @Test
    void should_keep_a_finished_dryer_waiting_while_its_drum_turns() {
        run(DRYER, 120, 480.0);
        run(DRYER, 7, 2.2);
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHED);

        for (int i = 0; i < 10; i++) {
            run(DRYER, 2, 190.0);
            run(DRYER, 1, 2.2);
        }

        assertThat(transitions).hasSize(2);
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHED);
    }

    //two samples are half a minute: still no end of the pause
    @Test
    void should_not_end_a_pause_on_a_draw_shorter_than_a_minute() {
        run(WASHER, 30, 300.0);
        run(WASHER, 1, 1.0);
        final Instant fall = now.minus(POLL);
        run(WASHER, 2, 300.0);
        run(WASHER, 9, 1.0);

        assertThat(transitions).last().isInstanceOfSatisfying(Transition.Finished.class,
            finished -> assertThat(finished.finishedAt()).isEqualTo(fall));
    }

    //a pause ended by a minute of draw counts the end again from the next fall
    @Test
    void should_count_the_end_from_the_next_fall_after_a_pause() {
        run(WASHER, 30, 300.0);
        run(WASHER, 4, 1.0);
        run(WASHER, 3, 300.0);
        final Instant fall = now;
        run(WASHER, 9, 1.0);
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHING);

        run(WASHER, 2, 1.0);

        assertThat(transitions).last().isInstanceOfSatisfying(Transition.Finished.class,
            finished -> assertThat(finished.finishedAt()).isEqualTo(fall));
    }

    @Test
    void should_discard_a_run_shorter_than_min_run_without_a_finish() {
        run(WASHER, 12, 300.0);         // 6 minutes - somebody switched it on and off again
        run(WASHER, 11, 0.0);

        assertThat(transitions).containsExactly(new Transition.Started(T0, false), new Transition.Discarded(T0));
        assertThat(detector.view().state()).isEqualTo(ApplianceState.IDLE);
    }

    //the dryer ends with a cool-down: the drum turns without the heater, well above idleBelowWatts -
    //still the cycle, which ends only when the drum stops
    @Test
    void should_end_a_dryer_after_its_cool_down() {
        run(DRYER, 90, 2300.0);         // 45 minutes heating
        run(DRYER, 20, 180.0);          // 10 minutes cool-down
        final Instant fall = now;
        run(DRYER, 7, 0.8);

        assertThat(transitions).hasSize(2).last()
            .isInstanceOfSatisfying(Transition.Finished.class, finished -> {
                assertThat(finished.startedAt()).isEqualTo(T0);
                assertThat(finished.finishedAt()).isEqualTo(fall);
            });
    }

    //an invalid sample or a failed poll never reaches the detector: the state holds, and time passes only
    //with the valid samples - a gap of failures neither finishes nor discards anything
    @Test
    void should_keep_the_state_over_invalid_samples() {
        run(WASHER, 30, 300.0);
        run(WASHER, 2, 1.0);
        now = now.plus(Duration.ofMinutes(20)); // the plug did not answer for 20 minutes
        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHING);

        run(WASHER, 1, 1.0);

        // the next valid sample below: finishedAfter has passed since the fall, the cycle is over
        assertThat(transitions).last().isInstanceOf(Transition.Finished.class);
    }

    @Test
    void should_count_the_energy_of_the_cycle_from_the_counter_of_the_plug() {
        run(WASHER, 60, 600.0);         // 30 min at 600 W = 300 Wh
        run(WASHER, 11, 0.0);

        assertThat(transitions).last().isInstanceOfSatisfying(Transition.Finished.class,
            finished -> assertThat(finished.energyWh()).isBetween(295L, 300L));
    }

    //the counter of the plug starts again at 0 after a restart of the plug
    @Test
    void should_count_the_energy_over_a_restart_of_the_plug() {
        run(WASHER, 30, 600.0);
        total = 0;
        run(WASHER, 30, 600.0);
        run(WASHER, 11, 0.0);

        assertThat(transitions).last().isInstanceOfSatisfying(Transition.Finished.class,
            finished -> assertThat(finished.energyWh()).isBetween(290L, 300L));
    }

    @Test
    void should_go_back_to_idle_when_a_finished_cycle_is_acknowledged() {
        run(WASHER, 30, 300.0);
        run(WASHER, 11, 0.0);

        assertThat(detector.acknowledge()).contains(new Transition.Acknowledged(T0));
        assertThat(detector.view()).isEqualTo(new CycleView(ApplianceState.IDLE, null, null, null));
        assertThat(detector.acknowledge()).isEmpty();
    }

    @Test
    void should_end_an_unacknowledged_finished_cycle_with_a_new_one() {
        run(WASHER, 30, 300.0);
        run(WASHER, 11, 0.0);
        final Instant second = now;

        run(WASHER, 3, 300.0);

        assertThat(transitions).last().isEqualTo(new Transition.Started(second, true));
        assertThat(detector.view().state()).isEqualTo(ApplianceState.RUNNING);
    }

    @Test
    void should_finish_a_cycle_restored_as_running() {
        final CycleDetector restored = CycleDetector.running(T0.minus(Duration.ofHours(1)));

        final Optional<Transition> fall = restored.sample(T0, 0.0, null, WASHER);
        final Optional<Transition> end = restored.sample(T0.plus(Duration.ofMinutes(5)), 0.0, null, WASHER);

        assertThat(fall).isEmpty();
        assertThat(end).get().isInstanceOfSatisfying(Transition.Finished.class, finished -> {
            assertThat(finished.finishedAt()).isEqualTo(T0);
            assertThat(finished.energyWh()).isZero();
        });
    }

    @Test
    void should_read_the_thresholds_of_every_sample() {
        run(WASHER, 30, 300.0);

        run(new CycleThresholds(10, 400, Duration.ofMinutes(10), Duration.ofMinutes(5)), 1, 300.0);

        assertThat(detector.view().state()).isEqualTo(ApplianceState.FINISHING);
    }

    private void run(final CycleThresholds thresholds, final int samples, final double watts) {
        for (int i = 0; i < samples; i++) {
            detector.sample(now, watts, total, thresholds).ifPresent(transitions::add);
            total += Math.round(watts * POLL.toSeconds() / 60.0);
            now = now.plus(POLL);
        }
    }
}
