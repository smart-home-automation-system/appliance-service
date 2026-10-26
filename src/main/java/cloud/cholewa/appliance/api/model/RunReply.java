package cloud.cholewa.appliance.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//A cycle of an appliance: when it started and when the draw fell for good (the clock of the house), the
//energy in watt-hours and the highest draw in watts as the pod saw them; a cycle that runs has no end yet,
//acknowledgedAt comes with HAS-279 and acknowledgedBy - who acknowledged it in the application - with HAS-347.
//A field not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunReply(
    long id,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long energyWh,
    Integer maxWatts,
    LocalDateTime acknowledgedAt,
    String acknowledgedBy
) {
}
