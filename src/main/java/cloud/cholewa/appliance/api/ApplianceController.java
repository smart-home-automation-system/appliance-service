package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.ApplianceReply;
import cloud.cholewa.appliance.api.model.RunReply;
import cloud.cholewa.appliance.api.model.SampleReply;
import cloud.cholewa.appliance.database.model.ApplianceRunEntity;
import cloud.cholewa.appliance.database.repository.ApplianceRunRepository;
import cloud.cholewa.appliance.database.repository.ApplianceSampleRepository;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

//The read API of the appliances (HAS-278), under the base path /home/appliance; routed by the gateway with
//HAS-281. Reads only - the acknowledgement is HAS-279's
@RestController
@RequiredArgsConstructor
public class ApplianceController {

    //a local date-time without an offset (2026-10-24T00:00:00), read by the clock of the house - the rules
    //of the other histories. A pattern instead of ISO.DATE_TIME, which takes an offset as well and silently
    //drops it; an offset or a zone is a 400
    static final String LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";
    static final Duration MAX_RUNS_SPAN = Duration.ofDays(31);
    static final Duration MAX_SAMPLES_SPAN = Duration.ofDays(2);

    private final ApplianceMonitor applianceMonitor;
    private final ApplianceRunRepository applianceRunRepository;
    private final ApplianceSampleRepository applianceSampleRepository;
    private final Clock clock;

    @GetMapping
    Mono<List<ApplianceReply>> appliances() {
        return Mono.fromSupplier(() -> applianceMonitor.statuses().stream().map(this::reply).toList());
    }

    @GetMapping("{id}")
    Mono<ApplianceReply> appliance(@PathVariable final long id) {
        return Mono.fromSupplier(() -> applianceMonitor.status(id).map(this::reply)
            .orElseThrow(() -> ApplianceException.notFound(id)));
    }

    //the cycles that started in [from, to), at most 31 days
    @GetMapping("{id}/runs")
    Flux<RunReply> runs(
        @PathVariable final long id,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return watched(id).thenMany(Flux.defer(() -> {
            final Range range = range(from, to, MAX_RUNS_SPAN, "31 days");
            return applianceRunRepository
                .findByDeviceIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAt(id, range.from(), range.to())
                .map(this::reply);
        }));
    }

    //the samples taken in [from, to), at most 2 days - the chart
    @GetMapping("{id}/samples")
    Flux<SampleReply> samples(
        @PathVariable final long id,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return watched(id).thenMany(Flux.defer(() -> {
            final Range range = range(from, to, MAX_SAMPLES_SPAN, "2 days");
            return applianceSampleRepository.findByDeviceIdAndAtGreaterThanEqualAndAtLessThanOrderByAt(id, range.from(), range.to())
                .map(sample -> new SampleReply(local(sample.at()), sample.watts()));
        }));
    }

    //the history of an appliance the service watches: one switched off or removed in the registry answers 404,
    //though its rows stay in the database - the dashboard reads the history of the appliances it lists
    private Mono<Void> watched(final long id) {
        return Mono.fromRunnable(() -> applianceMonitor.status(id).orElseThrow(() -> ApplianceException.notFound(id)));
    }

    //the bounds by the clock of the house; the span is counted on the wall clock (a fixed offset, not the zone
    //of the house), so two days across a change of the clocks are two days. A bound out of what the database holds fails the span or the order first
    private Range range(final LocalDateTime from, final LocalDateTime to, final Duration max, final String maxText) {
        if (!to.isAfter(from)) {
            throw new ServerWebInputException("to must be after from");
        }
        if (Duration.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC)).compareTo(max) > 0) {
            throw new ServerWebInputException("from and to must be at most " + maxText + " apart");
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
            local(run.acknowledgedAt()));
    }

    private LocalDateTime local(final Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private record Range(Instant from, Instant to) {
    }
}
