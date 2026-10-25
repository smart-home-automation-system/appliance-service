package cloud.cholewa.appliance.cycle;

import com.fasterxml.jackson.annotation.JsonValue;

//Where the cycle of one appliance stands (HAS-278). The words are what the read API answers - wire
//contract of the dashboard, pinned by ApplianceStateTest
public enum ApplianceState {

    //nothing runs; also a finished cycle somebody acknowledged
    IDLE("idle"),
    //the draw stayed above runningAboveWatts for a minute: a cycle runs
    RUNNING("running"),
    //the draw fell below idleBelowWatts; a pause of a washing machine, or the end
    FINISHING("finishing"),
    //the draw stayed below idleBelowWatts for finishedAfterMinutes after a run of at least minRunMinutes:
    //the cycle has finished and waits for somebody to empty the appliance
    FINISHED("finished");

    private final String word;

    ApplianceState(final String word) {
        this.word = word;
    }

    @JsonValue
    public String word() {
        return word;
    }
}
