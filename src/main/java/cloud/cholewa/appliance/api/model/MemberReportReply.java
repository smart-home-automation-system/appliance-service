package cloud.cholewa.appliance.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

//The chores report of HAS-345 for the cycles that finished in [from, to) (the clock of the house):
//- members: every switched-on member of the household registry, one with nothing done too, with a line per appliance
//  and their totals;
//- former: names met in the cycles that are no member any more, the same way;
//- unattributed: per appliance, the cycles completed by a mute without anybody confirming them;
//- unknownPresence: the cycles whose snapshot at the finish is not known - their opportunities are not counted.
//A count: opportunities (cycles finished while the member was at home), done (cycles attributed to the member while
//at home at the completion or not known, a shared one counting half), doneAway (attributed while known to be away) and
//share (done / opportunities, left out without an opportunity). A field not known is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberReportReply(
    LocalDateTime from,
    LocalDateTime to,
    List<Member> members,
    List<Member> former,
    List<Unattributed> unattributed,
    long unknownPresence
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String member, List<ApplianceCount> appliances, Count total) {
    }

    //name: of the device registry, left out for an appliance the service does not watch any more
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApplianceCount(long applianceId, String name, long opportunities, double done, double doneAway,
                                 Double share) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Count(long opportunities, double done, double doneAway, Double share) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Unattributed(long applianceId, String name, long count) {
    }
}
