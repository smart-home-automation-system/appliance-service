package cloud.cholewa.appliance.attribution;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

//Whose chore a cycle is (HAS-344), as one pure rule over what the run holds and what has just arrived. Owner
//2026-10-30: a tag read (nfc) and "Emptied" in the application with a member (app) are equal ways to confirm; only a
//mute without a member leaves a cycle unattributed.
//- nothing yet: a confirmation completes the cycle and attributes it to its member (single); a mute completes it
//  unattributed;
//- unattributed (muted): a later confirmation attributes it, the completion stays the mute's;
//- single: a different member within the window counted from the completion makes it shared (two names, half
//  each); the same member again, a member after the window, or a mute change nothing;
//- shared: nothing changes it.
//Empty when the event changes nothing. Names are compared whatever their case - they come checked by the registry
public final class AttributionRule {

    private AttributionRule() {
    }

    public static Optional<Completion> apply(final Completion current, final Event event, final Duration window) {
        if (current == null) {
            return Optional.of(switch (event) {
                case Confirmed confirmed -> new Completion(confirmed.at(), confirmed.source(),
                    List.of(confirmed.member()), Attribution.SINGLE);
                case Muted muted -> new Completion(muted.at(), Source.MUTE, List.of(), Attribution.UNATTRIBUTED);
            });
        }
        if (!(event instanceof Confirmed confirmed)) {
            return Optional.empty();
        }
        return switch (current.attribution()) {
            case UNATTRIBUTED -> Optional.of(new Completion(current.completedAt(), current.source(),
                List.of(confirmed.member()), Attribution.SINGLE));
            case SINGLE -> Optional.of(current)
                .filter(single -> single.attributedTo().stream().noneMatch(confirmed.member()::equalsIgnoreCase))
                .filter(single -> !confirmed.at().isAfter(single.completedAt().plus(window)))
                .map(single -> new Completion(single.completedAt(), single.source(),
                    List.of(single.attributedTo().getFirst(), confirmed.member()), Attribution.SHARED));
            case SHARED -> Optional.empty();
        };
    }

    //how a cycle was completed first; the word is what appliance_run stores
    public enum Source {
        NFC("nfc"), APP("app"), MUTE("mute");

        private final String word;

        Source(final String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }

        public static Optional<Source> of(final String word) {
            return Arrays.stream(values()).filter(source -> source.word.equals(word)).findFirst();
        }
    }

    public enum Attribution {
        SINGLE("single"), SHARED("shared"), UNATTRIBUTED("unattributed");

        private final String word;

        Attribution(final String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }

        public static Optional<Attribution> of(final String word) {
            return Arrays.stream(values()).filter(attribution -> attribution.word.equals(word)).findFirst();
        }
    }

    //what a completed cycle holds: when and how it was completed first, and whose chore it is
    public record Completion(Instant completedAt, Source source, List<String> attributedTo, Attribution attribution) {
    }

    public sealed interface Event permits Confirmed, Muted {
        Instant at();
    }

    //a member confirmed it - with the tag (nfc) or in the application (app)
    public record Confirmed(String member, Source source, Instant at) implements Event {
    }

    //acknowledged without a member
    public record Muted(Instant at) implements Event {
    }
}
