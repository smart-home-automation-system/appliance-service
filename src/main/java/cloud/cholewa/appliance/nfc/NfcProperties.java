package cloud.cholewa.appliance.nfc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code appliance.nfc} - the NFC tags on the appliances (HAS-343).<br>
 * {@code key}: the AES-128 key every tag computes its SUN MAC with, 32 hex characters - the secret
 * {@code appliance-nfc-key}, a required property. Checked here and never part of a message: a wrong key fails the
 * start without its value in the log ({@code toString} leaves it out as well).<br>
 * {@code tags}: the UID of each tag (14 hex characters, any case) and the id of its appliance in the device
 * registry - in the manifest while there are three, moved to the registry when there are more.
 */
@ConfigurationProperties(prefix = "appliance.nfc")
public record NfcProperties(
    String key,
    @DefaultValue Map<String, Long> tags
) {

    private static final Pattern KEY = Pattern.compile("\\p{XDigit}{32}");
    private static final Pattern UID = Pattern.compile("\\p{XDigit}{14}");

    public NfcProperties {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("appliance.nfc.key must be 32 hex characters (AES-128)");
        }
        tags.forEach((uid, deviceId) -> {
            if (!UID.matcher(uid).matches() || deviceId == null) {
                throw new IllegalArgumentException("appliance.nfc.tags must map a UID of 14 hex characters to a device id: " + uid);
            }
        });
        tags = tags.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey().toUpperCase(Locale.ROOT), Map.Entry::getValue));
    }

    public byte[] keyBytes() {
        return HexFormat.of().parseHex(key);
    }

    @Override
    public String toString() {
        return "NfcProperties[key=(hidden), tags=" + tags + "]";
    }
}
