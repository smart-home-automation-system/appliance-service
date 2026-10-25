package cloud.cholewa.appliance.power;

import cloud.cholewa.appliance.cycle.CycleThresholds;
import cloud.cholewa.appliance.registry.Appliance;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.Gen1Meter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Gen1PowerReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final Appliance DISHWASHER = new Appliance(51, "Dishwasher", RoomName.KITCHEN, "192.0.2.51", 0, 1,
        new CycleThresholds(10, 3, Duration.ofMinutes(10), Duration.ofMinutes(5)));

    private final ShellyGen1Client shelly = mock(ShellyGen1Client.class);
    private final Gen1PowerReader sut = new Gen1PowerReader(shelly, Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    //the time of the pod, not the timestamp of the plug - its local time written as if it were UTC
    @Test
    void should_read_the_draw_and_the_counter_of_the_meter_of_the_channel() {
        when(shelly.meter("192.0.2.51", 0)).thenReturn(Mono.just(new Gen1Meter()
            .power(0.33).isValid(true).total(110175L).timestamp(1792879321L)));

        sut.read(DISHWASHER).as(StepVerifier::create)
            .expectNext(PowerSample.reading(NOW, 0.33, 110175L))
            .verifyComplete();
    }

    @Test
    void should_take_a_reading_the_meter_does_not_hold_as_invalid() {
        when(shelly.meter("192.0.2.51", 0)).thenReturn(Mono.just(new Gen1Meter().power(0.0).isValid(false)));

        sut.read(DISHWASHER).as(StepVerifier::create).expectNext(PowerSample.invalid(NOW)).verifyComplete();
    }

    @Test
    void should_take_a_failed_poll_as_unanswered() {
        when(shelly.meter("192.0.2.51", 0)).thenReturn(Mono.error(new IllegalStateException("timeout")));

        sut.read(DISHWASHER).as(StepVerifier::create).expectNext(PowerSample.unanswered(NOW)).verifyComplete();
    }

    @Test
    void should_take_the_time_of_a_sample_to_the_whole_second() {
        final Gen1PowerReader reader = new Gen1PowerReader(shelly, Clock.fixed(NOW.plusMillis(808), ZoneId.of("Europe/Warsaw")));
        when(shelly.meter("192.0.2.51", 0)).thenReturn(Mono.just(new Gen1Meter().power(1.0).isValid(true)));

        reader.read(DISHWASHER).as(StepVerifier::create).expectNext(PowerSample.reading(NOW, 1.0, null)).verifyComplete();
    }

    //Spring subscribes again to the same Mono of a pass - the call is made anew every time
    @Test
    void should_read_the_plug_anew_for_every_subscription() {
        when(shelly.meter("192.0.2.51", 0)).thenReturn(Mono.just(new Gen1Meter().power(1.0).isValid(true)),
            Mono.just(new Gen1Meter().power(2.0).isValid(true)));
        final Mono<PowerSample> read = sut.read(DISHWASHER);

        read.as(StepVerifier::create).expectNext(PowerSample.reading(NOW, 1.0, null)).verifyComplete();
        read.as(StepVerifier::create).expectNext(PowerSample.reading(NOW, 2.0, null)).verifyComplete();
    }
}
