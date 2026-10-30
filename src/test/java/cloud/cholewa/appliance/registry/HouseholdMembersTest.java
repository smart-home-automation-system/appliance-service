package cloud.cholewa.appliance.registry;

import cloud.cholewa.home.model.HouseholdProfile;
import cloud.cholewa.home.model.MemberRole;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-345: the members of the report, kept five minutes, the last list answered while the registry is away
class HouseholdMembersTest {

    private static final Instant T0 = Instant.parse("2026-10-30T18:00:00Z");

    private final HouseholdProfilesClient profiles = mock(HouseholdProfilesClient.class);
    private Instant now = T0;
    private final HouseholdMembers sut = new HouseholdMembers(profiles, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Warsaw");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void should_keep_the_members_for_five_minutes() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))), Mono.just(List.of(profile("Jan"))));

        sut.names().as(StepVerifier::create).expectNext(List.of("Anna")).verifyComplete();
        now = T0.plus(HouseholdMembers.CACHED_FOR).minusSeconds(1);
        sut.names().as(StepVerifier::create).expectNext(List.of("Anna")).verifyComplete();
        now = T0.plus(HouseholdMembers.CACHED_FOR);
        sut.names().as(StepVerifier::create).expectNext(List.of("Jan")).verifyComplete();

        verify(profiles, times(2)).getProfiles();
    }

    @Test
    void should_answer_the_last_members_while_the_registry_is_away() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))),
            Mono.error(new RegistryCallException("database-service answered 503")));

        sut.names().as(StepVerifier::create).expectNext(List.of("Anna")).verifyComplete();
        now = T0.plus(HouseholdMembers.CACHED_FOR);
        sut.names().as(StepVerifier::create).expectNext(List.of("Anna")).verifyComplete();
    }

    @Test
    void should_fail_when_the_registry_was_never_read() {
        when(profiles.getProfiles()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        sut.names().as(StepVerifier::create).expectError(RegistryCallException.class).verify();
    }

    private static HouseholdProfile profile(final String name) {
        return HouseholdProfile.builder().name(name).role(MemberRole.RESIDENT).build();
    }
}
