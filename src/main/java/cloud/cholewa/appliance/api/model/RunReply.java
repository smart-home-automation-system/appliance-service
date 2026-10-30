package cloud.cholewa.appliance.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

//A cycle of an appliance: when it started and when the draw fell for good (the clock of the house), the
//energy in watt-hours and the highest draw in watts as the pod saw them; a cycle that runs has no end yet,
//acknowledgedAt comes with HAS-279 and acknowledgedBy - who acknowledged it in the application - with HAS-347.
//HAS-344: when and how it was completed first (completionSource nfc, app or mute), whose chore it is
//(attributedTo, attribution single, shared or unattributed) and who was at home when it finished and when it was
//completed - an empty list is nobody. A field not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunReply(
    long id,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long energyWh,
    Integer maxWatts,
    LocalDateTime acknowledgedAt,
    String acknowledgedBy,
    LocalDateTime completedAt,
    String completionSource,
    List<String> attributedTo,
    String attribution,
    List<String> presentAtFinish,
    List<String> presentAtCompletion
) {
}
