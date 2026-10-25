package cloud.cholewa.appliance.monitor;

import cloud.cholewa.appliance.cycle.CycleDetector;
import cloud.cholewa.appliance.power.PowerSample;
import cloud.cholewa.appliance.registry.Appliance;

import java.util.concurrent.atomic.AtomicReference;

//An appliance the service watches: what the registry says of it (replaced by every refresh), its detector,
//the row of its cycle and the last poll. The registry refresh and the sampling pass run on two threads of
//the scheduler and the read API on a third: the fields are atomic references to immutable records, the
//detector is synchronized, the row and the samples are written by the sampling pass only
final class TrackedAppliance {

    private final AtomicReference<Appliance> appliance;
    private final CycleDetector detector;
    private final AtomicReference<Long> runId;
    private final AtomicReference<PowerSample> lastPoll = new AtomicReference<>();
    private final AtomicReference<PowerSample> lastReading = new AtomicReference<>();

    TrackedAppliance(final Appliance appliance, final CycleDetector detector, final Long runId) {
        this.appliance = new AtomicReference<>(appliance);
        this.detector = detector;
        this.runId = new AtomicReference<>(runId);
    }

    Appliance appliance() {
        return appliance.get();
    }

    void appliance(final Appliance appliance) {
        this.appliance.set(appliance);
    }

    CycleDetector detector() {
        return detector;
    }

    Long runId() {
        return runId.get();
    }

    void runId(final Long runId) {
        this.runId.set(runId);
    }

    void polled(final PowerSample sample) {
        lastPoll.set(sample);
        if (sample.valid()) {
            lastReading.set(sample);
        }
    }

    PowerSample lastPoll() {
        return lastPoll.get();
    }

    PowerSample lastReading() {
        return lastReading.get();
    }
}
