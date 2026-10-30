package cloud.cholewa.appliance.attribution;

import cloud.cholewa.appliance.attribution.AttributionRule.Attribution;
import cloud.cholewa.appliance.attribution.AttributionRule.Completion;
import cloud.cholewa.appliance.attribution.AttributionRule.Confirmed;
import cloud.cholewa.appliance.attribution.AttributionRule.Muted;
import cloud.cholewa.appliance.attribution.AttributionRule.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The rule of HAS-344 without I/O (owner 2026-10-30: a tag read and "Emptied" in the application are equal)
class AttributionRuleTest {

    private static final Instant T0 = Instant.parse("2026-10-30T16:47:45Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"NFC", "APP"})
    void should_attribute_a_cycle_to_the_member_of_a_single_confirmation(final Source source) {
        assertThat(AttributionRule.apply(null, new Confirmed("Tobiasz", source, T0), WINDOW))
            .contains(new Completion(T0, source, List.of("Tobiasz"), Attribution.SINGLE));
    }

    @Test
    void should_share_a_cycle_two_different_members_confirmed_within_the_window() {
        final Completion first = single("Anna", Source.NFC);

        assertThat(AttributionRule.apply(first, new Confirmed("Jan", Source.APP, T0.plus(WINDOW)), WINDOW))
            .contains(new Completion(T0, Source.NFC, List.of("Anna", "Jan"), Attribution.SHARED));
    }

    @Test
    void should_not_share_a_cycle_with_a_member_after_the_window() {
        assertThat(AttributionRule.apply(single("Anna", Source.NFC),
            new Confirmed("Jan", Source.NFC, T0.plus(WINDOW).plusSeconds(1)), WINDOW)).isEmpty();
    }

    //Tobiasz on 2026-10-30: "Emptied" in the application first, his tag later - his cycle either way
    @Test
    void should_ignore_a_repeated_confirmation_of_the_same_member_by_either_way() {
        final Completion first = single("Tobiasz", Source.APP);

        assertThat(AttributionRule.apply(first, new Confirmed("Tobiasz", Source.NFC, T0.plusSeconds(60)), WINDOW)).isEmpty();
        assertThat(AttributionRule.apply(first, new Confirmed("tobiasz", Source.APP, T0.plusSeconds(60)), WINDOW)).isEmpty();
    }

    @Test
    void should_keep_a_shared_cycle_as_it_is() {
        final Completion shared = new Completion(T0, Source.NFC, List.of("Anna", "Jan"), Attribution.SHARED);

        assertThat(AttributionRule.apply(shared, new Confirmed("Ola", Source.NFC, T0.plusSeconds(30)), WINDOW)).isEmpty();
    }

    @Test
    void should_complete_a_muted_cycle_unattributed() {
        assertThat(AttributionRule.apply(null, new Muted(T0), WINDOW))
            .contains(new Completion(T0, Source.MUTE, List.of(), Attribution.UNATTRIBUTED));
    }

    //only a mute without a member is unattributed: a confirmation later still attributes the cycle
    @Test
    void should_attribute_a_muted_cycle_confirmed_later() {
        final Completion muted = new Completion(T0, Source.MUTE, List.of(), Attribution.UNATTRIBUTED);

        assertThat(AttributionRule.apply(muted, new Confirmed("Anna", Source.NFC, T0.plus(Duration.ofHours(2))), WINDOW))
            .contains(new Completion(T0, Source.MUTE, List.of("Anna"), Attribution.SINGLE));
    }

    @Test
    void should_never_take_an_attribution_back_with_a_mute() {
        assertThat(AttributionRule.apply(single("Anna", Source.APP), new Muted(T0.plusSeconds(5)), WINDOW)).isEmpty();
        assertThat(AttributionRule.apply(new Completion(T0, Source.MUTE, List.of(), Attribution.UNATTRIBUTED),
            new Muted(T0.plusSeconds(5)), WINDOW)).isEmpty();
    }

    //the words stored in appliance_run, pinned: the report of HAS-345 reads them
    @Test
    void should_store_the_words_of_v6() {
        assertThat(List.of(Source.values())).extracting(Source::word).containsExactly("nfc", "app", "mute");
        assertThat(List.of(Attribution.values())).extracting(Attribution::word)
            .containsExactly("single", "shared", "unattributed");
    }

    private static Completion single(final String member, final Source source) {
        return new Completion(T0, source, List.of(member), Attribution.SINGLE);
    }
}
