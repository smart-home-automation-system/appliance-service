package cloud.cholewa.appliance.api.model;

import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.home.model.RoomName;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//An appliance as GET /home/appliance answers it. The times are the clock of the house without an offset,
//as every read of the dashboard. A field not known is left out, never sent as a default: watts and
//sampledAt before the first valid reading since the start of the pod, reachable before the first poll,
//runningSince outside a cycle, finishedAt and awaitingSince unless finished
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplianceReply(
    long id,
    String name,
    RoomName room,
    ApplianceState state,
    Double watts,
    LocalDateTime sampledAt,
    LocalDateTime runningSince,
    LocalDateTime finishedAt,
    LocalDateTime awaitingSince,
    Boolean reachable
) {
}
