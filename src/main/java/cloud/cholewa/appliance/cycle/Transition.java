package cloud.cholewa.appliance.cycle;

import java.time.Instant;

//What a sample changed in the cycle of an appliance - what has to be stored, and from HAS-279 on what the
//household is told. A sample that changes nothing gives none
public sealed interface Transition {

    //a cycle runs: the draw has been above runningAboveWatts since startedAt, for a minute.
    //replacedUnacknowledged - a finished cycle nobody acknowledged was waiting; the new one ends it
    record Started(Instant startedAt, boolean replacedUnacknowledged) implements Transition {
    }

    //the cycle has finished: the draw fell below idleBelowWatts at finishedAt and stayed there for
    //finishedAfterMinutes. The energy and the highest draw are of the whole cycle, as far as this
    //instance saw it
    record Finished(Instant startedAt, Instant finishedAt, long energyWh, int maxWatts) implements Transition {
    }

    //the draw fell for good before minRunMinutes - not a cycle: nobody is told, nothing is kept
    record Discarded(Instant startedAt) implements Transition {
    }

    //the finished cycle was acknowledged (HAS-279) or claimed by a tag read (HAS-343) - the same transition
    record Acknowledged(Instant startedAt) implements Transition {
    }
}
