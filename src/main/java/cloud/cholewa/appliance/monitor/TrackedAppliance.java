package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.CycleDetector;
import cloud.cholewa.appliance.power.PowerSample;
import cloud.cholewa.appliance.registry.Appliance;

//An appliance the service watches: what the registry says of it (replaced by every refresh), its detector,
//the row of its cycle and the last poll. The registry refresh and the sampling pass run on two threads of
//the scheduler and the read API on a third: the fields are volatile, the detector is synchronized, the
//row and the samples are written by the sampling pass only
final class TrackedAppliance {

    private volatile Appliance appliance;
    private final CycleDetector detector;
    private volatile Long runId;
    private volatile PowerSample lastPoll;
    private volatile PowerSample lastReading;

    TrackedAppliance(final Appliance appliance, final CycleDetector detector, final Long runId) {
        this.appliance = appliance;
        this.detector = detector;
        this.runId = runId;
    }

    Appliance appliance() {
        return appliance;
    }

    void appliance(final Appliance appliance) {
        this.appliance = appliance;
    }

    CycleDetector detector() {
        return detector;
    }

    Long runId() {
        return runId;
    }

    void runId(final Long runId) {
        this.runId = runId;
    }

    void polled(final PowerSample sample) {
        lastPoll = sample;
        if (sample.valid()) {
            lastReading = sample;
        }
    }

    PowerSample lastPoll() {
        return lastPoll;
    }

    PowerSample lastReading() {
        return lastReading;
    }
}
