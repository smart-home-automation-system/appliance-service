package cloud.cholewa.appliance.availability;

import cloud.cholewa.appliance.availability.PlugAvailability.Alert;
import cloud.cholewa.appliance.availability.PlugAvailability.Decision;
import cloud.cholewa.appliance.availability.PlugAvailability.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The rules of the availability monitor of the plugs (HAS-280), without I/O
class PlugAvailabilityTest {

    private static final Instant NOW = Instant.parse("2026-10-26T18:00:00Z");
    private static final PlugMonitorProperties PROPERTIES = new PlugMonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1));

    @Test
    void should_take_a_plug_as_out_after_five_minutes_without_a_reading() {
        final Watched fourMinutes = new Watched(131, "Washing machine", "boiler", NOW.minusSeconds(299), true);
        final Watched fiveMinutes = new Watched(132, "Dryer", "bathroom up", NOW.minusSeconds(300), true);

        final Decision decision = PlugAvailability.decide(List.of(fourMinutes, fiveMinutes), Map.of(), NOW, PROPERTIES);

        assertThat(decision.alerts()).containsExactly(new Alert(132, "Dryer", "bathroom up", NOW.minusSeconds(300), NOW));
    }

    @Test
    void should_remind_every_hour_while_a_plug_stays_out() {
        final Watched dryer = new Watched(132, "Dryer", "bathroom up", NOW.minus(Duration.ofHours(2)), true);
        final Alert told = new Alert(132, "Dryer", "bathroom up", dryer.failingSince(), NOW.minus(Duration.ofMinutes(59)));

        assertThat(PlugAvailability.decide(List.of(dryer), Map.of(132L, told), NOW, PROPERTIES).reminders()).isEmpty();

        final Alert hourAgo = new Alert(132, "Dryer", "bathroom up", dryer.failingSince(), NOW.minus(Duration.ofHours(1)));
        final Decision decision = PlugAvailability.decide(List.of(dryer), Map.of(132L, hourAgo), NOW, PROPERTIES);
        assertThat(decision.reminders()).containsExactly(hourAgo);
        assertThat(decision.alerts()).isEmpty();
    }

    @Test
    void should_announce_the_return_of_a_plug_that_reads_again() {
        final Watched dryer = new Watched(132, "Dryer", "bathroom up", null, true);
        final Alert told = new Alert(132, "Dryer", "bathroom up", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));

        assertThat(PlugAvailability.decide(List.of(dryer), Map.of(132L, told), NOW, PROPERTIES).returns()).containsExactly(told);
    }

    //after a start nothing was polled yet: neither out nor back
    @Test
    void should_say_nothing_of_a_plug_not_polled_since_the_start() {
        final Watched unpolled = new Watched(132, "Dryer", "bathroom up", null, false);
        final Alert told = new Alert(132, "Dryer", "bathroom up", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(30)));

        final Decision decision = PlugAvailability.decide(List.of(unpolled), Map.of(132L, told), NOW, PROPERTIES);

        assertThat(decision.alerts()).isEmpty();
        assertThat(decision.reminders()).isEmpty();
        assertThat(decision.returns()).isEmpty();
    }

    //switched off or removed in the registry while out: its alert goes without a word
    @Test
    void should_forget_the_alert_of_a_plug_no_longer_watched() {
        final Alert told = new Alert(133, "Dishwasher", "kitchen", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(10)));

        final Decision decision = PlugAvailability.decide(List.of(), Map.of(133L, told), NOW, PROPERTIES);

        assertThat(decision.forgotten()).containsExactly(told);
        assertThat(decision.returns()).isEmpty();
    }
}
