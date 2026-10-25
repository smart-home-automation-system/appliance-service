package cloud.cholewa.appliance.cycle;

import java.time.Instant;

//The cycle of an appliance as the read API shows it; what the state does not have is null
//runningSince - RUNNING and FINISHING; finishedAt and awaitingSince - FINISHED
public record CycleView(ApplianceState state, Instant runningSince, Instant finishedAt, Instant awaitingSince) {
}
