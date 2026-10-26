package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.api.model.ApplianceReply;
import cloud.cholewa.appliance.api.model.RunReply;
import cloud.cholewa.appliance.api.model.SampleReply;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.registry.HouseholdProfilesClient;
import cloud.cholewa.home.model.HouseholdProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

//What the read API answers (HAS-278): the appliances as the monitor holds them, their cycles and samples from
//the database. The rules of a range, the lookup of the appliance (404 NOT_FOUND_APPLIANCE) and the clock of
//the house are here; the controller only takes the request. A range that breaks a rule is a 400 without a
//code, as the other histories answer it (ResponseStatusException, as WeatherHistoryService)
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplianceService {

    static final Duration MAX_RUNS_SPAN = Duration.ofDays(31);
    static final Duration MAX_SAMPLES_SPAN = Duration.ofDays(2);

    private final ApplianceMonitor applianceMonitor;
    private final ApplianceRunRepository applianceRunRepository;
    private final ApplianceSampleRepository applianceSampleRepository;
    private final HouseholdProfilesClient householdProfilesClient;
    private final Clock clock;

    public Mono<List<ApplianceReply>> appliances() {
        return Mono.fromSupplier(() -> applianceMonitor.statuses().stream().map(this::reply).toList());
    }

    public Mono<ApplianceReply> appliance(final long id) {
        return watched(id).map(this::reply);
    }

    //the finished cycle acknowledged (HAS-279) - a mute meant for the administrator once the tag claims of
    //HAS-343 acknowledge for the members: 404 not watched, 409 NOTHING_TO_ACKNOWLEDGE when nothing waits.
    //The member who acknowledges it (HAS-347, optional) is checked first against the profiles of the household,
    //whatever the case, and stored and told by the name the registry has: 400 UNKNOWN_MEMBER, and nothing is
    //acknowledged. A failed read of the profiles never holds the acknowledgement back - it goes without a name
    public Mono<Void> acknowledge(final long id, final String member) {
        return Mono.justOrEmpty(member)
            .map(String::strip)
            .filter(name -> !name.isEmpty())
            .flatMap(this::registeredName)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(name -> applianceMonitor.acknowledge(id, name.orElse(null)));
    }

    private Mono<String> registeredName(final String member) {
        return householdProfilesClient.getProfiles()
            .flatMap(profiles -> Mono.justOrEmpty(profiles.stream()
                    .map(HouseholdProfile::getName)
                    .filter(member::equalsIgnoreCase)
                    .findFirst())
                .switchIfEmpty(Mono.error(() -> ApplianceException.unknownMember(member))))
            .onErrorResume(e -> !(e instanceof ApplianceException), e -> {
                log.warn("The member who acknowledges could not be checked, acknowledged without a name: {}", e.toString());
                return Mono.empty();
            });
    }

    //the cycles that started in [from, to), at most 31 days
    public Flux<RunReply> runs(final long id, final LocalDateTime from, final LocalDateTime to) {
        return watched(id).flatMapMany(status -> {
            final Range range = range(from, to, MAX_RUNS_SPAN, "31 days");
            return applianceRunRepository
                .findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(id, range.from(), range.to())
                .map(this::reply);
        });
    }

    //the samples taken in [from, to), at most 2 days - the chart
    public Flux<SampleReply> samples(final long id, final LocalDateTime from, final LocalDateTime to) {
        return watched(id).flatMapMany(status -> {
            final Range range = range(from, to, MAX_SAMPLES_SPAN, "2 days");
            return applianceSampleRepository.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(id, range.from(), range.to())
                .map(sample -> new SampleReply(local(sample.at()), sample.watts()));
        });
    }

    //an appliance the service watches: one switched off or removed in the registry answers 404, though its rows
    //stay in the database - the dashboard reads the history of the appliances it lists
    private Mono<ApplianceStatus> watched(final long id) {
        return Mono.defer(() -> applianceMonitor.status(id)
            .map(Mono::just)
            .orElseGet(() -> Mono.error(ApplianceException.notFound(id))));
    }

    //the bounds by the clock of the house; the span is counted on the wall clock (a fixed offset, not the zone
    //of the house), so two days across a change of the clocks are two days
    private Range range(final LocalDateTime from, final LocalDateTime to, final Duration max, final String maxText) {
        if (!to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from");
        }
        if (Duration.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC)).compareTo(max) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to must be at most " + maxText + " apart");
        }
        return new Range(from.atZone(clock.getZone()).toInstant(), to.atZone(clock.getZone()).toInstant());
    }

    private ApplianceReply reply(final ApplianceStatus status) {
        return new ApplianceReply(
            status.appliance().id(),
            status.appliance().name(),
            status.appliance().room(),
            status.cycle().state(),
            status.watts(),
            local(status.sampledAt()),
            local(status.cycle().runningSince()),
            local(status.cycle().finishedAt()),
            local(status.cycle().awaitingSince()),
            status.reachable()
        );
    }

    private RunReply reply(final ApplianceRunEntity run) {
        return new RunReply(run.id(), local(run.startedAt()), local(run.finishedAt()), run.energyWh(), run.maxWatts(),
            local(run.acknowledgedAt()), run.acknowledgedBy());
    }

    private LocalDateTime local(final Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private record Range(Instant from, Instant to) {
    }
}
