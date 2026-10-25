package cloud.cholewa.appliance.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.ApplianceSettings;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplianceRegistryTest {

    private static final ApplianceSettings SETTINGS = ApplianceSettings.builder()
        .runningAboveWatts(10).idleBelowWatts(3).minRunMinutes(10).finishedAfterMinutes(5)
        .reminderEveryMinutes(30).maxReminders(3).quietFrom("22:00").quietTo("07:00")
        .build();

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final ApplianceRegistry sut = new ApplianceRegistry(client);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(ApplianceRegistry.class)).addAppender(log);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ApplianceRegistry.class)).detachAppender(log);
    }

    //a device it cannot watch is said once, not every five minutes - and again once it came back after a fix
    @Test
    void should_say_once_which_appliance_it_does_not_watch() {
        final SmartDevice gen1 = device(51, 1);
        final SmartDevice gen3 = device(52, 3);
        when(client.getAppliances()).thenReturn(Mono.just(List.of(gen1, gen3)), Mono.just(List.of(gen1, gen3)),
            Mono.just(List.of(gen1)), Mono.just(List.of(gen1, gen3)));

        sut.read().as(StepVerifier::create)
            .assertNext(appliances -> assertThat(appliances).extracting(Appliance::id).containsExactly(51L))
            .verifyComplete();
        sut.read().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("52", "Gen2+");
        });

        sut.read().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        sut.read().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        assertThat(log.list).hasSize(2);
    }

    //nothing answered: the monitor keeps the appliances it has
    @Test
    void should_answer_nothing_when_the_registry_cannot_be_read() {
        when(client.getAppliances()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        sut.read().as(StepVerifier::create).verifyComplete();

        assertThat(log.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    private static SmartDevice device(final long id, final int generation) {
        return SmartDevice.builder()
            .id(id).name("Plug").room(RoomName.KITCHEN).kind(DeviceKind.APPLIANCE).enabled(true).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("192.0.2." + id).generation(generation).build())
            .appliance(SETTINGS)
            .build();
    }
}
