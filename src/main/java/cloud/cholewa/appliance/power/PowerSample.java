package cloud.cholewa.appliance.power;

import java.time.Instant;

//One poll of the meter of a plug, at the time of the pod. answered - the plug answered at all (the
//availability monitor of HAS-280 counts the rest); valid - the answer holds a reading. watts and
//totalWattMinutes are set only for a valid sample, totalWattMinutes only when the plug counts energy
public record PowerSample(Instant at, boolean answered, boolean valid, Double watts, Long totalWattMinutes) {

    public static PowerSample reading(final Instant at, final double watts, final Long totalWattMinutes) {
        return new PowerSample(at, true, true, watts, totalWattMinutes);
    }

    public static PowerSample invalid(final Instant at) {
        return new PowerSample(at, true, false, null, null);
    }

    public static PowerSample unanswered(final Instant at) {
        return new PowerSample(at, false, false, null, null);
    }
}
