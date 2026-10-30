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
    NOT_FOUND_APPLIANCE("Appliance not found"),

    //the appliance has no finished cycle waiting to be acknowledged (HAS-279)
    NOTHING_TO_ACKNOWLEDGE("Nothing to acknowledge"),

    //the member who acknowledges is no switched-on member of the household registry (HAS-347)
    UNKNOWN_MEMBER("Unknown household member"),

    //a tag read that is not a tag of an appliance: an unknown UID or a MAC the key does not give (HAS-343)
    INVALID_TAG("Invalid tag"),

    //a tag read whose counter is not above the last one taken - a link read before, copied or bookmarked (HAS-343)
    REPLAYED_TAG("Replayed tag read"),

    //the appliance of the tag has no cycle finished within the claim window (HAS-343)
    NOTHING_TO_CLAIM("Nothing to claim");

    private final String description;
}
