package cloud.cholewa.appliance.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//A cycle of an appliance: when it started and when the draw fell for good (the clock of the house), the
//energy in watt-hours and the highest draw in watts as the pod saw them; a cycle that runs has no end yet,
//and acknowledgedAt comes with HAS-279. A field not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunReply(
    long id,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long energyWh,
    Integer maxWatts,
    LocalDateTime acknowledgedAt
) {
}
