package cloud.cholewa.appliance.service;

import cloud.cholewa.appliance.api.model.MemberReportReply;
import cloud.cholewa.appliance.api.model.MemberReportReply.ApplianceCount;
import cloud.cholewa.appliance.api.model.MemberReportReply.Count;
import cloud.cholewa.appliance.api.model.MemberReportReply.Member;
import cloud.cholewa.appliance.api.model.MemberReportReply.Unattributed;
import cloud.cholewa.appliance.database.repository.MemberReportRepository;
import cloud.cholewa.appliance.database.repository.MemberReportRepository.ReportRow;
import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.appliance.monitor.ApplianceMonitor;
import cloud.cholewa.appliance.monitor.ApplianceStatus;
import cloud.cholewa.appliance.registry.HouseholdMembers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

//The chores report per member (HAS-345): the range checked (400 INVALID_RANGE), the rows of one aggregation
//(MemberReportRepository) put together with the members of the registry - every member listed, one with nothing done
//with zeros on every appliance the service watches - and the names of the appliances the monitor holds. A name met in
//the cycles that is no member any more is listed under former. The registry that cannot be read (and was never read)
//is a 503: members and former cannot be told apart without it
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    static final Duration MAX_SPAN = Duration.ofDays(366);

    private final MemberReportRepository memberReportRepository;
    private final HouseholdMembers householdMembers;
    private final ApplianceMonitor applianceMonitor;
    private final Clock clock;

    public Mono<MemberReportReply> members(final LocalDateTime from, final LocalDateTime to) {
        return Mono.defer(() -> {
            checkRange(from, to);
            return householdMembers.names()
                .onErrorMap(e -> {
                    log.warn("The household profiles of the chores report could not be read: {}", e.toString());
                    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The household registry could not be read");
                })
                .zipWith(memberReportRepository.report(from.atZone(clock.getZone()).toInstant(),
                    to.atZone(clock.getZone()).toInstant()).collectList())
                .map(both -> reply(from, to, both.getT1(), both.getT2()));
        });
    }

    //on the wall clock, as the other ranges: 366 days across a change of the clocks are 366 days
    private static void checkRange(final LocalDateTime from, final LocalDateTime to) {
        if (!to.isAfter(from)) {
            throw ApplianceException.invalidRange("to must be after from");
        }
        if (Duration.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC)).compareTo(MAX_SPAN) > 0) {
            throw ApplianceException.invalidRange("from and to must be at most 366 days apart");
        }
    }

    private MemberReportReply reply(final LocalDateTime from, final LocalDateTime to, final List<String> memberNames,
                                    final List<ReportRow> rows) {
        final Map<Long, String> appliances = new LinkedHashMap<>();
        applianceMonitor.statuses().stream()
            .map(ApplianceStatus::appliance)
            .forEach(appliance -> appliances.put(appliance.id(), appliance.name()));

        final List<ReportRow> memberRows = rows.stream().filter(row -> "member".equals(row.kind())).toList();
        final TreeSet<Long> devices = new TreeSet<>(appliances.keySet());
        memberRows.forEach(row -> devices.add(row.deviceId()));

        final List<Member> members = memberNames.stream()
            .map(name -> member(name, rowsOf(memberRows, name), devices, appliances))
            .toList();
        final List<Member> former = memberRows.stream()
            .map(ReportRow::member)
            .filter(name -> memberNames.stream().noneMatch(name::equalsIgnoreCase))
            .map(name -> name.toLowerCase(Locale.ROOT))
            .distinct()
            .map(lower -> {
                final List<ReportRow> own = rowsOf(memberRows, lower);
                return member(own.getFirst().member(), own, new TreeSet<>(own.stream().map(ReportRow::deviceId).toList()), appliances);
            })
            .sorted(Comparator.comparing(Member::member))
            .toList();
        final List<Unattributed> unattributed = rows.stream()
            .filter(row -> "unattributed".equals(row.kind()))
            .sorted(Comparator.comparing(ReportRow::deviceId))
            .map(row -> new Unattributed(row.deviceId(), appliances.get(row.deviceId()), row.opportunities()))
            .toList();
        final long unknownPresence = rows.stream()
            .filter(row -> "unknown".equals(row.kind()))
            .mapToLong(ReportRow::opportunities)
            .sum();

        return new MemberReportReply(from, to, members, former, unattributed, unknownPresence);
    }

    private static List<ReportRow> rowsOf(final List<ReportRow> rows, final String name) {
        return rows.stream().filter(row -> row.member().equalsIgnoreCase(name)).toList();
    }

    private static Member member(final String name, final List<ReportRow> rows, final TreeSet<Long> devices,
                                 final Map<Long, String> appliances) {
        final List<ApplianceCount> counts = new ArrayList<>();
        for (final Long device : devices) {
            final Optional<ReportRow> row = rows.stream().filter(one -> device.equals(one.deviceId())).findFirst();
            final long opportunities = row.map(ReportRow::opportunities).orElse(0L);
            final double done = row.map(ReportRow::done).map(ReportService::rounded).orElse(0.0);
            final double doneAway = row.map(ReportRow::doneAway).map(ReportService::rounded).orElse(0.0);
            counts.add(new ApplianceCount(device, appliances.get(device), opportunities, done, doneAway,
                share(done, opportunities)));
        }
        final long opportunities = counts.stream().mapToLong(ApplianceCount::opportunities).sum();
        final double done = counts.stream().mapToDouble(ApplianceCount::done).sum();
        final double doneAway = counts.stream().mapToDouble(ApplianceCount::doneAway).sum();
        return new Member(name, counts, new Count(opportunities, done, doneAway, share(done, opportunities)));
    }

    private static double rounded(final BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    //done may pass the opportunities - a member who came home after the finish and emptied it
    private static Double share(final double done, final long opportunities) {
        return opportunities == 0 ? null
            : BigDecimal.valueOf(done / opportunities).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
