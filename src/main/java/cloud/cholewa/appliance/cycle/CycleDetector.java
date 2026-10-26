package cloud.cholewa.appliance.cycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

//The cycle of one appliance read from the draw of its plug (HAS-278) - pure, no I/O, no clock: every
//decision is made by the time of the sample it is given. One instance per appliance, called one sample
//at a time; the thresholds come with every sample, so a change in the registry counts from the next one.
//
//IDLE -> RUNNING   the draw stays above runningAboveWatts for START_AFTER (a minute); the cycle starts
//                  at the first sample above
//RUNNING -> FINISHING   a sample below idleBelowWatts
//FINISHING -> RUNNING   the draw stays at or above idleBelowWatts for START_AFTER (a minute) - a washing
//                       machine going on after a pause; a shorter draw (the anti-crease turns of a dryer
//                       that waits to be emptied, HAS-285) is ignored and the end still counts from the fall
//FINISHING -> FINISHED  finishedAfterMinutes below idleBelowWatts, when the cycle lasted at least
//                       minRunMinutes (start to the fall); a shorter one is discarded, back to IDLE
//FINISHED -> IDLE       acknowledged or claimed (HAS-279, HAS-343)
//FINISHING -> IDLE      acknowledged before the end was certain (HAS-348) - ended at the fall, never FINISHED
//FINISHED -> RUNNING    a new cycle starts, by the rule of IDLE
//
//An invalid sample (is_valid false, a failed poll) is not given to the detector: it keeps the state.
//Time passes only with the samples, so a gap of failed polls neither finishes nor starts anything
public final class CycleDetector {

    public static final Duration START_AFTER = Duration.ofMinutes(1);

    private ApplianceState state;
    //the start of the cycle (RUNNING, FINISHING, FINISHED)
    private Instant startedAt;
    //the first sample of a draw that may start a cycle (IDLE, FINISHED) or end a pause (FINISHING)
    private Instant aboveSince;
    //the first sample below idleBelowWatts (FINISHING), the end of the cycle (FINISHED)
    private Instant finishedAt;
    private Instant awaitingSince;
    private final Energy energy = new Energy();
    private int maxWatts;

    private CycleDetector(final ApplianceState state, final Instant startedAt, final Instant finishedAt,
                          final Instant awaitingSince) {
        this.state = state;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.awaitingSince = awaitingSince;
    }

    public static CycleDetector idle() {
        return new CycleDetector(ApplianceState.IDLE, null, null, null);
    }

    //a cycle restored at the start of the pod from its row: the energy and the highest draw count again
    //from here, what the pod before saw of them is in the row
    public static CycleDetector running(final Instant startedAt) {
        return new CycleDetector(ApplianceState.RUNNING, startedAt, null, null);
    }

    public static CycleDetector finished(final Instant startedAt, final Instant finishedAt, final Instant awaitingSince) {
        return new CycleDetector(ApplianceState.FINISHED, startedAt, finishedAt, awaitingSince);
    }

    //one valid sample: the draw in watts at a time, and the energy counter of the plug in watt-minutes
    //(null when the plug does not count)
    public synchronized Optional<Transition> sample(final Instant at, final double watts, final Long totalWattMinutes,
                                                   final CycleThresholds thresholds) {
        energy.add(totalWattMinutes);
        return switch (state) {
            case IDLE, FINISHED -> whileNotRunning(at, watts, thresholds);
            case RUNNING -> whileRunning(at, watts, thresholds);
            case FINISHING -> whileFinishing(at, watts, thresholds);
        };
    }

    //FINISHED -> IDLE; nothing to acknowledge in any other state
    public synchronized Optional<Transition> acknowledge() {
        if (state != ApplianceState.FINISHED) {
            return Optional.empty();
        }
        final Instant cycle = startedAt;
        toIdle();
        return Optional.of(new Transition.Acknowledged(cycle));
    }

    //FINISHING -> IDLE at once (HAS-348): somebody emptied the appliance before its end was certain. The end of
    //the cycle is the fall of the draw, as it would have been; the cycle never becomes FINISHED, so it is never
    //announced or reminded. A cycle shorter than minRunMinutes counts too (owner, 2026-10-26: the six minutes of
    //a refresh of the dryer): minRunMinutes keeps a draw nobody saw from being announced, and a member who says
    //they emptied it saw it. Any other state stays as it is
    public synchronized Optional<Transition.Finished> acknowledgeFinishing() {
        if (state != ApplianceState.FINISHING) {
            return Optional.empty();
        }
        final Transition.Finished finished = new Transition.Finished(startedAt, finishedAt, energy.wattHours(), maxWatts);
        toIdle();
        return Optional.of(finished);
    }

    //the undo of acknowledgeFinishing when its row could not be stored: FINISHING again, from the same fall - the
    //next sample ends it as it would have ended (its row written, the household told), unless a new cycle
    //started meanwhile
    public synchronized void reopen(final Transition.Finished finished) {
        if (state != ApplianceState.IDLE || aboveSince != null) {
            return;
        }
        state = ApplianceState.FINISHING;
        startedAt = finished.startedAt();
        finishedAt = finished.finishedAt();
        maxWatts = Math.max(maxWatts, finished.maxWatts());
    }

    public synchronized CycleView view() {
        return switch (state) {
            case IDLE -> new CycleView(state, null, null, null);
            case RUNNING, FINISHING -> new CycleView(state, startedAt, null, null);
            case FINISHED -> new CycleView(state, startedAt, finishedAt, awaitingSince);
        };
    }

    private Optional<Transition> whileNotRunning(final Instant at, final double watts, final CycleThresholds thresholds) {
        if (watts <= thresholds.runningAboveWatts()) {
            aboveSince = null;
            return Optional.empty();
        }
        if (aboveSince == null) {
            aboveSince = at;
            energy.restart();
            maxWatts = 0;
        }
        maxWatts = Math.max(maxWatts, (int) Math.round(watts));
        if (Duration.between(aboveSince, at).compareTo(START_AFTER) < 0) {
            return Optional.empty();
        }
        final boolean replaced = state == ApplianceState.FINISHED;
        state = ApplianceState.RUNNING;
        startedAt = aboveSince;
        aboveSince = null;
        finishedAt = null;
        awaitingSince = null;
        return Optional.of(new Transition.Started(startedAt, replaced));
    }

    private Optional<Transition> whileRunning(final Instant at, final double watts, final CycleThresholds thresholds) {
        maxWatts = Math.max(maxWatts, (int) Math.round(watts));
        if (watts < thresholds.idleBelowWatts()) {
            state = ApplianceState.FINISHING;
            finishedAt = at;
            aboveSince = null;
        }
        return Optional.empty();
    }

    private Optional<Transition> whileFinishing(final Instant at, final double watts, final CycleThresholds thresholds) {
        if (watts >= thresholds.idleBelowWatts()) {
            maxWatts = Math.max(maxWatts, (int) Math.round(watts));
            if (aboveSince == null) {
                aboveSince = at;
            }
            if (Duration.between(aboveSince, at).compareTo(START_AFTER) >= 0) {
                state = ApplianceState.RUNNING;
                finishedAt = null;
                aboveSince = null;
            }
            return Optional.empty();
        }
        aboveSince = null;
        if (Duration.between(finishedAt, at).compareTo(thresholds.finishedAfter()) < 0) {
            return Optional.empty();
        }
        if (Duration.between(startedAt, finishedAt).compareTo(thresholds.minRun()) < 0) {
            final Instant cycle = startedAt;
            toIdle();
            return Optional.of(new Transition.Discarded(cycle));
        }
        state = ApplianceState.FINISHED;
        awaitingSince = at;
        return Optional.of(new Transition.Finished(startedAt, finishedAt, energy.wattHours(), maxWatts));
    }

    private void toIdle() {
        state = ApplianceState.IDLE;
        startedAt = null;
        aboveSince = null;
        finishedAt = null;
        awaitingSince = null;
    }

    //The energy of a cycle from the counter of the plug, in watt-minutes: the sum of the steps between two
    //samples. The counter starts again at 0 when the plug restarts - a step down counts what the counter
    //holds since then
    private static final class Energy {

        private Long last;
        private long wattMinutes;

        void add(final Long total) {
            if (total == null) {
                return;
            }
            if (last != null) {
                wattMinutes += total >= last ? total - last : total;
            }
            last = total;
        }

        void restart() {
            wattMinutes = 0;
        }

        long wattHours() {
            return Math.round(wattMinutes / 60.0);
        }
    }
}
