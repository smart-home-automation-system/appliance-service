package cloud.cholewa.appliance.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses. Their names are the codes of the answer (ErrorId.codeOf) - wire
//contract for a caller that branches on them, the dashboard first of all, pinned by ApplianceErrorTest.
//A range that breaks a rule is a 400 without a code, as the other histories answer it
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ApplianceError implements ErrorId {

    //no appliance of the registry the service watches has this id
    NOT_FOUND_APPLIANCE("Appliance not found");

    private final String description;
}
