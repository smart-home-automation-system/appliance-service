package cloud.cholewa.appliance.database.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;

//The chores report of HAS-345 as one aggregation over appliance_run per call (no query per member or appliance), of
//the cycles that finished in [from, to). Names are matched whatever their case (lower on both sides) - the lists are
//text arrays of names as the registry spelled them; the display name is the first spelling met.
//Rows of three kinds:
//- member: per appliance and name - opportunities (cycles with the name in present_at_finish), done (cycles attributed
//  to the name while it was at home at the completion or the snapshot is not known, a shared cycle counting half) and
//  doneAway (attributed while the snapshot at the completion is known and the name is not in it);
//- unattributed: per appliance, the cycles completed by a mute without anybody confirming them;
//- unknown: the cycles whose snapshot at the finish is not known (null) - their opportunities cannot be counted.
@Repository
@RequiredArgsConstructor
public class MemberReportRepository {

    static final String REPORT = """
        WITH finished AS (
            SELECT * FROM appliance_run WHERE finished_at >= :from AND finished_at < :to
        ),
        chances AS (
            SELECT f.device_id, lower(name) AS member, min(name) AS shown, count(*) AS opportunities
            FROM finished f CROSS JOIN LATERAL unnest(f.present_at_finish) AS name
            GROUP BY f.device_id, lower(name)
        ),
        attributed AS (
            SELECT f.device_id, lower(name) AS member, min(name) AS shown,
                   sum(CASE WHEN away THEN 0 ELSE 1.0 / cardinality(f.attributed_to) END) AS done,
                   sum(CASE WHEN away THEN 1.0 / cardinality(f.attributed_to) ELSE 0 END) AS done_away
            FROM finished f
                CROSS JOIN LATERAL unnest(f.attributed_to) AS name
                CROSS JOIN LATERAL (SELECT f.present_at_completion IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM unnest(f.present_at_completion) AS present WHERE lower(present) = lower(name)
                ) AS away) AS presence
            WHERE f.attribution IN ('single', 'shared')
            GROUP BY f.device_id, lower(name)
        )
        SELECT 'member' AS kind, coalesce(c.device_id, a.device_id) AS device_id, coalesce(c.shown, a.shown) AS member,
               coalesce(c.opportunities, 0) AS opportunities, coalesce(a.done, 0) AS done,
               coalesce(a.done_away, 0) AS done_away
        FROM chances c FULL JOIN attributed a ON c.device_id = a.device_id AND c.member = a.member
        UNION ALL
        SELECT 'unattributed', device_id, NULL, count(*), 0, 0 FROM finished
        WHERE attribution = 'unattributed' GROUP BY device_id
        UNION ALL
        SELECT 'unknown', NULL, NULL, count(*), 0, 0 FROM finished WHERE present_at_finish IS NULL
        """;

    private final DatabaseClient databaseClient;

    public Flux<ReportRow> report(final Instant from, final Instant to) {
        return databaseClient.sql(REPORT)
            .bind("from", from)
            .bind("to", to)
            .map((row, metadata) -> new ReportRow(
                row.get("kind", String.class),
                row.get("device_id", Long.class),
                row.get("member", String.class),
                row.get("opportunities", Long.class),
                row.get("done", BigDecimal.class),
                row.get("done_away", BigDecimal.class)))
            .all();
    }

    //kind member, unattributed or unknown; what a kind does not have is null or zero
    public record ReportRow(String kind, Long deviceId, String member, Long opportunities, BigDecimal done,
                            BigDecimal doneAway) {
    }
}
