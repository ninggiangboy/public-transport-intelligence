package dev.pti.etl.metrics;

import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.EtlSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;

/**
 * Gauges of {@code etl-batch} that come from the warehouse, refreshed every minute so they are right after a restart
 * (DOC-28 §2, §3.4): open dead letters by source and status, the ACTIVE GTFS feed and its days to expiry, and the
 * validation issues of the last feed loaded.
 */
public class OpsGauges {

    private static final Logger log = LoggerFactory.getLogger(OpsGauges.class);

    /** Every status that still needs someone or something to act (DOC-22 §1.3). */
    static final List<String> OPEN_STATUSES = List.of(
            "NEW", "TRIAGING", "TRIAGED", "AUTO_REPLAY_SCHEDULED", "PENDING_CONFIRM", "MANUAL", "REPLAY_REQUESTED");

    private final JdbcTemplate jdbc;
    private final BusinessClock clock;
    private final Map<String, Double> openRecords = new ConcurrentHashMap<>();
    private final MultiGauge activeFeed;
    private final MultiGauge validationIssues;
    private volatile double daysToExpiry = Double.NaN;

    public OpsGauges(JdbcTemplate jdbc, BusinessClock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        for (EtlSource source : EtlSource.values()) {
            for (String status : OPEN_STATUSES) {
                String key = source.name() + "|" + status;
                openRecords.put(key, 0.0);
                Gauge.builder("pti.dlq.open.records", openRecords, m -> m.getOrDefault(key, 0.0))
                        .tag("source", source.name())
                        .tag("status", status)
                        .register(meters);
            }
        }
        this.activeFeed = MultiGauge.builder("pti.gtfs.active.feed.info").register(meters);
        this.validationIssues = MultiGauge.builder("pti.gtfs.validation.issues").register(meters);
        Gauge.builder("pti.gtfs.active.feed.days.to.expiry", this, g -> g.daysToExpiry)
                .register(meters);
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000, scheduler = "ptiTaskScheduler")
    public void refresh() {
        try {
            refreshDeadLetters();
            refreshFeed();
        } catch (RuntimeException e) {
            log.warn("Cannot refresh the operations gauges: {}", e.toString());
        }
    }

    private void refreshDeadLetters() {
        Map<String, Double> counts = new TreeMap<>();
        jdbc.query(
                """
                SELECT source::text AS source, status, count(*) AS n FROM ops.dead_letter
                WHERE status = ANY (?) GROUP BY 1, 2
                """,
                rs -> {
                    counts.put(rs.getString("source") + "|" + rs.getString("status"), (double) rs.getLong("n"));
                },
                (Object) OPEN_STATUSES.toArray(String[]::new));
        openRecords.replaceAll((key, old) -> counts.getOrDefault(key, 0.0));
    }

    private void refreshFeed() {
        List<MultiGauge.Row<?>> active = new ArrayList<>();
        jdbc.query("""
                SELECT feed_version_id, feed_hash, valid_to, agency_timezone FROM dw.gtfs_feed_version
                WHERE status = 'ACTIVE'
                """, rs -> {
            LocalDate validTo = rs.getObject("valid_to", LocalDate.class);
            active.add(MultiGauge.Row.of(
                    Tags.of(
                            "feed_version_id", Long.toString(rs.getLong("feed_version_id")),
                            "feed_hash", rs.getString("feed_hash").substring(0, 8),
                            "valid_to", String.valueOf(validTo)),
                    1));
            ZoneId zone = ZoneId.of(rs.getString("agency_timezone"));
            LocalDate today = clock.instant().atZone(zone).toLocalDate();
            daysToExpiry = validTo == null ? Double.NaN : ChronoUnit.DAYS.between(today, validTo);
        });
        if (active.isEmpty()) {
            daysToExpiry = Double.NaN;
        }
        activeFeed.register(active, true);

        List<String> reports = jdbc.queryForList(
                "SELECT validation_report::text FROM dw.gtfs_feed_version WHERE validation_report IS NOT NULL"
                        + " ORDER BY loaded_at DESC LIMIT 1",
                String.class);
        List<MultiGauge.Row<?>> issues = new ArrayList<>();
        if (!reports.isEmpty()) {
            JsonNode report = MessageJson.mapper().readTree(reports.getFirst());
            addIssues(issues, report.path("errors"), "error");
            addIssues(issues, report.path("warnings"), "warning");
        }
        validationIssues.register(issues, true);
    }

    private static void addIssues(List<MultiGauge.Row<?>> rows, JsonNode entries, String level) {
        for (JsonNode entry : entries) {
            rows.add(MultiGauge.Row.of(
                    Tags.of("check", entry.path("check").asString(), "level", level),
                    entry.path("count").asLong(1)));
        }
    }
}
