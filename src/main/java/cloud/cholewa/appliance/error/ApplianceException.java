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
}
