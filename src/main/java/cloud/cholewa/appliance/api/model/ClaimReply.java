package cloud.cholewa.appliance.api.model;

import cloud.cholewa.appliance.cycle.ApplianceState;

import java.time.LocalDateTime;

//A claim taken (HAS-343) - what the tag page shows: the appliance, where it stands now (idle once the claim
//acknowledged it), the member by the name of the registry, the cycle claimed and when (the clock of the house)
public record ClaimReply(
    long applianceId,
    String name,
    ApplianceState state,
    String member,
    long runId,
    LocalDateTime claimedAt
) {
}
