package cloud.cholewa.appliance.api.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

//What the tag page of the dashboard read from the URL of a tag (HAS-343, HAS-346): picc_data (the UID and the read
//counter encrypted, 16 bytes) and the SUN MAC (8 bytes), each in hex of any case, and the member of the profile of
//the page
public record ClaimRequest(
    @NotNull @Pattern(regexp = "\\p{XDigit}{32}") String piccData,
    @NotNull @Pattern(regexp = "\\p{XDigit}{16}") String cmac,
    @NotNull @Size(min = 3, max = 50) String member
) {
}
