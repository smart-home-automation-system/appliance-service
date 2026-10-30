package cloud.cholewa.appliance.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

//A request the API refuses: the status says whose problem it is, the error names the cause, the message is
//answered as details
@Getter
public class ApplianceException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final ApplianceError error;

    public ApplianceException(final HttpStatus httpStatus, final ApplianceError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static ApplianceException notFound(final long id) {
        return new ApplianceException(HttpStatus.NOT_FOUND, ApplianceError.NOT_FOUND_APPLIANCE,
            "No appliance with id " + id + " is watched");
    }

    public static ApplianceException nothingToAcknowledge(final long id) {
        return new ApplianceException(HttpStatus.CONFLICT, ApplianceError.NOTHING_TO_ACKNOWLEDGE,
            "Appliance " + id + " has no finished cycle waiting to be acknowledged");
    }

    public static ApplianceException unknownMember(final String member) {
        return new ApplianceException(HttpStatus.BAD_REQUEST, ApplianceError.UNKNOWN_MEMBER,
            "No switched-on household member is named " + member);
    }

    //the UID only - never the counter or the MAC, which say nothing to the caller
    public static ApplianceException invalidTag() {
        return new ApplianceException(HttpStatus.BAD_REQUEST, ApplianceError.INVALID_TAG,
            "The read is not a valid read of a tag of an appliance");
    }

    public static ApplianceException replayedTag(final String uid) {
        return new ApplianceException(HttpStatus.CONFLICT, ApplianceError.REPLAYED_TAG,
            "The read of tag " + uid + " was taken before");
    }

    public static ApplianceException nothingToClaim(final long id) {
        return new ApplianceException(HttpStatus.CONFLICT, ApplianceError.NOTHING_TO_CLAIM,
            "Appliance " + id + " has no cycle finished within the claim window");
    }
}
