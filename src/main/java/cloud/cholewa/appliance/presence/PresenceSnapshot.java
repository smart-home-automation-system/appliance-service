package cloud.cholewa.appliance.presence;

import java.util.List;

//Who was at home at a moment (HAS-344): the names presence-service gave as present - empty when nobody was - or
//null when it is not known (the call failed). Stored as it is: null never reads as "nobody"
public record PresenceSnapshot(List<String> present) {

    public static PresenceSnapshot of(final List<String> present) {
        return new PresenceSnapshot(List.copyOf(present));
    }

    public static PresenceSnapshot unknown() {
        return new PresenceSnapshot(null);
    }
}
