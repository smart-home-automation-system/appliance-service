package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.api.model.ClaimReply;
import cloud.cholewa.appliance.api.model.ClaimRequest;
import cloud.cholewa.appliance.attribution.AttributionRule.Source;
import cloud.cholewa.appliance.attribution.RunAttribution;
import cloud.cholewa.appliance.config.ApplianceProperties;
import cloud.cholewa.appliance.cycle.ApplianceState;
import cloud.cholewa.appliance.database.model.ApplianceClaimEntity;
import cloud.cholewa.appliance.database.repository.ApplianceClaimRepository;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceTagRepository;
import cloud.cholewa.appliance.error.ApplianceError;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.nfc.NfcProperties;
import cloud.cholewa.appliance.nfc.SunVerifier;
import cloud.cholewa.appliance.nfc.SunVerifier.TagRead;
import cloud.cholewa.appliance.registry.HouseholdProfilesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

//A chore claimed with the NFC tag on an appliance (HAS-343), step by step, each refusing on its own:
//1. the tag - picc_data the key decrypts into a UID and a counter, the SUN MAC the key gives for them, and a UID of
//   appliance.nfc.tags; otherwise 400 INVALID_TAG, logged at WARN (with the UID only when it was decrypted);
//2. its appliance - one the monitor watches, otherwise 404 NOT_FOUND_APPLIANCE;
//3. the member - a switched-on member of the household registry, otherwise 400 UNKNOWN_MEMBER; a registry that
//   cannot be read is a 503 (an unchecked name never goes into a claim), and the read can be repeated with the same
//   URL, since nothing was taken yet;
//4. the counter - taken only when it is above the last one of the tag (one statement), otherwise 409 REPLAYED_TAG;
//   from here the same URL is spent, whatever follows;
//5. the cycle - a finished one waiting (or still finishing) is acknowledged by the path of the application
//   (ApplianceMonitor.acknowledge: the reminders stop, "appliance acknowledged" goes with the member); then the claim
//   attaches to the latest cycle that finished within appliance.claim-window, acknowledged before or not - a second
//   member emptying it too (HAS-344); none: 409 NOTHING_TO_CLAIM.
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimService {

    private static final Set<ApplianceState> WAITING = Set.of(ApplianceState.FINISHED, ApplianceState.FINISHING);

    private final SunVerifier sunVerifier;
    private final NfcProperties nfcProperties;
    private final ApplianceProperties applianceProperties;
    private final ApplianceMonitor applianceMonitor;
    private final ApplianceTagRepository applianceTagRepository;
    private final ApplianceRunRepository applianceRunRepository;
    private final ApplianceClaimRepository applianceClaimRepository;
    private final HouseholdProfilesClient householdProfilesClient;
    private final RunAttribution runAttribution;
    private final Clock clock;

    public Mono<ClaimReply> claim(final ClaimRequest request) {
        return Mono.defer(() -> {
            final Instant now = Instant.now(clock);
            return verified(request.piccData(), request.cmac())
                .flatMap(read -> tagged(read)
                    .flatMap(this::watched)
                    .flatMap(status -> registeredName(request.member().strip())
                        .flatMap(member -> take(read.uid(), status.appliance().id(), read.counter(), now)
                            .then(Mono.defer(() -> attach(status, member, read.counter(), now))))));
        });
    }

    //a read the key does not give carries no UID worth logging - it is not decrypted into one
    private Mono<TagRead> verified(final String piccData, final String cmac) {
        return Mono.fromSupplier(() -> sunVerifier.read(piccData, cmac))
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("A tag read was refused: picc_data and its MAC are not what the key of the tags gives");
                return Mono.error(ApplianceException.invalidTag());
            }));
    }

    private Mono<Long> tagged(final TagRead read) {
        return Mono.justOrEmpty(nfcProperties.tags().get(read.uid()))
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("A read of tag {} was refused: not a tag of an appliance", read.uid());
                return Mono.error(ApplianceException.invalidTag());
            }));
    }

    private Mono<ApplianceStatus> watched(final long deviceId) {
        return Mono.defer(() -> applianceMonitor.status(deviceId)
            .map(Mono::just)
            .orElseGet(() -> Mono.error(ApplianceException.notFound(deviceId))));
    }

    private Mono<String> registeredName(final String member) {
        return householdProfilesClient.getProfiles()
            .onErrorMap(e -> {
                log.warn("The member of a tag claim could not be checked: {}", e.toString());
                return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The household registry could not be read");
            })
            .flatMap(profiles -> Mono.justOrEmpty(ApplianceService.registeredName(profiles, member)))
            .switchIfEmpty(Mono.error(() -> ApplianceException.unknownMember(member)));
    }

    private Mono<Void> take(final String uid, final long deviceId, final int counter, final Instant now) {
        return applianceTagRepository.advance(uid, deviceId, counter, now)
            .filter(taken -> taken > 0)
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("A read of tag {} with counter {} was refused: taken before", uid, counter);
                return Mono.error(ApplianceException.replayedTag(uid));
            }))
            .then();
    }

    private Mono<ClaimReply> attach(final ApplianceStatus status, final String member, final int counter, final Instant now) {
        final long deviceId = status.appliance().id();
        return Mono.just(status.cycle().state())
            .filter(WAITING::contains)
            //acknowledged meanwhile by somebody else: the claim still attaches below
            .flatMap(waiting -> applianceMonitor.acknowledge(deviceId, member)
                .onErrorResume(ClaimService::nothingToAcknowledge, e -> Mono.empty()))
            .then(Mono.defer(() -> applianceRunRepository.findFirstByDeviceIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(deviceId)))
            .filter(run -> !run.finishedAt().isBefore(now.minus(applianceProperties.claimWindow())))
            .switchIfEmpty(Mono.error(() -> ApplianceException.nothingToClaim(deviceId)))
            .flatMap(run -> applianceClaimRepository.save(ApplianceClaimEntity.builder()
                .runId(run.id())
                .member(member)
                .source(ApplianceClaimEntity.SOURCE_NFC)
                .claimedAt(now)
                .counter(counter)
                .build()))
            //HAS-344: the claim attributes the cycle - never fails the claim
            .flatMap(claim -> runAttribution.confirmed(claim.runId(), member, Source.NFC).thenReturn(claim))
            .map(claim -> {
                log.info("{} ({}): cycle {} claimed by {} with its tag", status.appliance().name(), deviceId, claim.runId(), member);
                return new ClaimReply(deviceId, status.appliance().name(),
                    applianceMonitor.status(deviceId).map(current -> current.cycle().state()).orElse(status.cycle().state()),
                    member, claim.runId(), LocalDateTime.ofInstant(claim.claimedAt(), clock.getZone()));
            });
    }

    private static boolean nothingToAcknowledge(final Throwable e) {
        return e instanceof ApplianceException refused && refused.getError() == ApplianceError.NOTHING_TO_ACKNOWLEDGE;
    }
}
