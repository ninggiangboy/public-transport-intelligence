package dev.pti.analytics.recompute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.TripUpdateRows;
import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.disruption.adapter.out.jdbc.JdbcDisruptionStore;
import dev.pti.analytics.disruption.adapter.out.metrics.MicrometerDisruptionMetrics;
import dev.pti.analytics.disruption.application.DisruptionDetector;
import dev.pti.analytics.disruption.domain.DisruptionAlerts;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcDisruptionRecomputeStore;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcRecomputeScopes;
import dev.pti.analytics.recompute.adapter.out.metrics.MicrometerRecomputeMetrics;
import dev.pti.analytics.recompute.application.DetectorRecomputeService;
import dev.pti.analytics.recompute.application.DisruptionRecompute;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.id.InsightIds;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The disruption recompute against the migrated warehouse (DOC-23 §18.7, AN-R-06 and AN-R-07). A route delivers six
 * arrivals a minute for two and a half hours; between 13:20 and 13:50 each arrives ten minutes late instead of one, so
 * the live detector opens one episode and closes it. The recompute has to give the rows the live detector gave: the
 * episode, the baseline and the hourly snapshots.
 */
class DisruptionRecomputeIT {

    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private static final Instant T0 = Instant.parse("2026-09-29T12:00:00Z");

    private final WarehouseSupport db = new WarehouseSupport();
    private final String route = "RC2-" + UUID.randomUUID().toString().substring(0, 8);
    private final JdbcDisruptionStore store = new JdbcDisruptionStore(db.jdbc);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();
    private final ManualClock manual = new ManualClock(T0);
    private final BusinessClock clock = new BusinessClock(manual, Duration.ZERO);

    private final AnalyticsReferenceCache reference = new AnalyticsReferenceCache() {
        @Override
        public boolean hasActiveFeed() {
            return true;
        }

        @Override
        public long feedVersionId() {
            return 1;
        }

        @Override
        public ZoneId agencyZone() {
            return ZoneId.of("America/Chicago");
        }

        @Override
        public Optional<RouteInfo> route(String routeId) {
            return routeId.equals(route)
                    ? Optional.of(new RouteInfo(route, 3, "RC2", Map.of(0, "Northbound", 1, "Southbound")))
                    : Optional.empty();
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return Optional.empty();
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hour) {
            return OptionalInt.empty();
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.of(serviceDate);
        }
    };

    private final DisruptionDetector detector = new DisruptionDetector(
            true,
            thresholds,
            store,
            reference,
            new JdbcAlertWriter(db.jdbc),
            new JdbcAdvisoryLock(db.jdbc),
            new JdbcTransactionLimits(db.jdbc),
            db.tx,
            new MicrometerAnalyticsMetrics(meters),
            new MicrometerDisruptionMetrics(meters),
            clock);

    @AfterEach
    void cleanUp() {
        for (String table : List.of(
                "dw.fact_trip_update",
                "insight.insight_service_disruption",
                "insight.analytics_route_baseline",
                "insight.analytics_baseline_snapshot",
                "ops.alert_event")) {
            db.jdbc
                    .sql("DELETE FROM " + table + " WHERE route_id = :r")
                    .param("r", route)
                    .update();
        }
    }

    private DetectorRecomputeService service() {
        DisruptionRecompute recompute = new DisruptionRecompute(
                true,
                thresholds,
                store,
                new JdbcDisruptionRecomputeStore(db.jdbc),
                new JdbcRecomputeScopes(db.jdbc),
                reference,
                new JdbcAlertWriter(db.jdbc),
                new JdbcAdvisoryLock(db.jdbc),
                new JdbcTransactionLimits(db.jdbc),
                db.tx,
                new RunReporter(new MicrometerAnalyticsMetrics(meters)),
                clock);
        return new DetectorRecomputeService(List.of(recompute), new MicrometerRecomputeMetrics(meters));
    }

    private static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    /** Six arrivals a minute for 150 minutes of direction 0; the delay jumps from 60 s to 600 s from 13:20 to 13:50. */
    private void traffic() {
        TripUpdateRows rows = new TripUpdateRows(new JdbcTemplate(db.dataSource), route);
        for (int minute = 0; minute < 150; minute++) {
            boolean burst = minute >= 80 && minute < 110;
            int delay = burst ? 600 : 60;
            for (int i = 0; i < 6; i++) {
                Instant observed = T0.plus(Duration.ofMinutes(minute)).plusSeconds(i * 9L);
                rows.arrival(DAY, observed.minusSeconds(delay), delay)
                        .stop("S" + i)
                        .insert();
            }
        }
    }

    /** The live detector over the traffic, half an hour of clock at a time (it catches up one hour at most). */
    private void runLive(String until) {
        Instant now = at("12:30:10");
        while (now.isBefore(at(until))) {
            liveAt(now);
            now = now.plus(Duration.ofMinutes(30));
        }
        liveAt(at(until));
    }

    private void liveAt(Instant now) {
        manual.set(now);
        detector.advance(
                route, Trigger.BATCH, RunContext.afterBatch(RunResult.newBatchId(), UUID.randomUUID(), T0, now, now));
    }

    private List<Map<String, Object>> episodes() {
        return db.jdbc.sql("""
                        SELECT id, route_id, direction_id, episode_start, episode_end, status, close_reason,
                               baseline_mean_seconds, baseline_stddev_seconds, current_avg_delay_seconds,
                               current_z_score, peak_avg_delay_seconds, peak_z_score, sample_count,
                               affected_stop_ids::text AS stops, last_bucket
                        FROM insight.insight_service_disruption WHERE route_id = :r ORDER BY episode_start""").param("r", route).query().listOfRows();
    }

    private List<Map<String, Object>> baselines() {
        return db
                .jdbc
                .sql("SELECT * FROM insight.analytics_route_baseline WHERE route_id = :r ORDER BY direction_id")
                .param("r", route)
                .query()
                .listOfRows()
                .stream()
                .map(row -> withoutKeys(row, "updated_at"))
                .toList();
    }

    private List<Map<String, Object>> snapshots() {
        return db.jdbc
                .sql("SELECT * FROM insight.analytics_baseline_snapshot WHERE route_id = :r"
                        + " ORDER BY snapshot_hour, direction_id")
                .param("r", route)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> alerts() {
        return db.jdbc
                .sql("SELECT dedup_key, severity, audience, title, CAST(body AS text) AS body,"
                        + " resolved_at IS NOT NULL AS resolved FROM ops.alert_event WHERE route_id = :r"
                        + " ORDER BY dedup_key")
                .param("r", route)
                .query()
                .listOfRows();
    }

    private static Map<String, Object> withoutKeys(Map<String, Object> row, String... keys) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        for (String key : keys) {
            copy.remove(key);
        }
        return copy;
    }

    private DetectorStats recompute(String from, String to) {
        DetectorRecomputeService service = service();
        List<WorkItem> items = service.plan(Set.of(Detector.DISRUPTION), at(from), at(to));
        assertThat(items).extracting(WorkItem::scope).containsExactly(route);
        return service.execute(items.getFirst());
    }

    @Test
    void recomputingFromASnapshotGivesTheEpisodeTheBaselineAndTheSnapshotsOfTheLiveRun() {
        traffic();
        runLive("14:40:10");
        List<Map<String, Object>> expectedEpisodes = episodes();
        List<Map<String, Object>> expectedBaselines = baselines();
        List<Map<String, Object>> expectedSnapshots = snapshots();
        List<Map<String, Object>> expectedAlerts = alerts();
        assertThat(expectedEpisodes)
                .as("one episode, closed once the delay was over")
                .hasSize(1);
        assertThat(expectedEpisodes.getFirst())
                .containsEntry("status", "CLOSED")
                .containsEntry("close_reason", "RECOVERED");
        UUID id = (UUID) expectedEpisodes.getFirst().get("id");
        // Damage everything a recompute writes: if it did not rewrite them from the 13:00 snapshot, it would show.
        db.jdbc
                .sql("UPDATE insight.insight_service_disruption SET peak_z_score = 1, sample_count = 1 WHERE id = :id")
                .param("id", id)
                .update();
        db.jdbc
                .sql("UPDATE insight.analytics_route_baseline SET ewma_mean = 0, bucket_count = 1 WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("UPDATE insight.analytics_baseline_snapshot SET ewma_mean = 0"
                        + " WHERE route_id = :r AND snapshot_hour >= :hour")
                .param("r", route)
                .param("hour", java.sql.Timestamp.from(at("14:00:00")))
                .update();

        DetectorStats stats = recompute("13:30:00", "14:00:00");

        assertThat(stats).isEqualTo(new DetectorStats(1, 1, 0));
        assertThat(episodes()).isEqualTo(expectedEpisodes);
        assertThat(baselines()).isEqualTo(expectedBaselines);
        assertThat(snapshots()).isEqualTo(expectedSnapshots);
        assertThat(alerts())
                .as("the alert of an episode that was reproduced closed is unchanged")
                .isEqualTo(expectedAlerts);
    }

    @Test
    void withoutASnapshotTheRecomputeStartsEmptyAndGivesTheSameResult() {
        traffic();
        runLive("14:40:10");
        List<Map<String, Object>> expectedEpisodes = episodes();
        List<Map<String, Object>> expectedBaselines = baselines();
        List<Map<String, Object>> expectedSnapshots = snapshots();
        db.jdbc
                .sql("DELETE FROM insight.analytics_baseline_snapshot WHERE route_id = :r")
                .param("r", route)
                .update();

        DetectorStats stats = recompute("12:00:00", "14:30:00");

        assertThat(stats.upserted()).isEqualTo(1);
        assertThat(episodes()).isEqualTo(expectedEpisodes);
        assertThat(baselines()).isEqualTo(expectedBaselines);
        assertThat(snapshots()).isEqualTo(expectedSnapshots);
    }

    @Test
    void anEpisodeThatTheReplayDoesNotReproduceIsDeletedAndItsAlertIsWithdrawn() {
        traffic();
        runLive("14:40:10");
        // An episode in direction 1 that no arrival supports, with the alert the live detector would have written.
        Instant start = at("13:35:00");
        DisruptionEpisode bogus = new DisruptionEpisode(
                InsightIds.disruption(route, 1, start),
                route,
                1,
                start,
                null,
                null,
                60,
                30,
                600,
                18,
                600,
                18,
                6,
                List.of("S1"),
                at("13:36:00"));
        store.saveEpisode(bogus, RunResult.newBatchId());
        new JdbcAlertWriter(db.jdbc)
                .open(new DisruptionAlerts(thresholds.severityHighZ())
                        .draft(bogus, reference.route(route).orElseThrow()));
        assertThat(episodes()).hasSize(2);

        DetectorStats stats = recompute("13:30:00", "14:00:00");

        assertThat(stats).isEqualTo(new DetectorStats(1, 1, 1));
        assertThat(episodes()).hasSize(1);
        Map<String, Object> alert = alerts().stream()
                .filter(row -> row.get("dedup_key").equals("disruption:" + bogus.id()))
                .findFirst()
                .orElseThrow();
        assertThat(alert).containsEntry("resolved", true);
        assertThat((String) alert.get("body")).contains("\"withdrawn\": true");
    }

    @Test
    void anEpisodeStillOpenAtTheLiveCursorIsHandedOverAndTheLiveRunClosesIt() {
        traffic();
        runLive("14:40:10");
        List<Map<String, Object>> expectedEpisodes = episodes();
        List<Map<String, Object>> expectedBaselines = baselines();
        cleanUp();
        traffic();
        // The live run stops at 13:41 with the episode open; its state is damaged before the recompute.
        runLive("13:41:10");
        assertThat(episodes()).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "OPEN"));
        db.jdbc
                .sql(
                        "UPDATE insight.analytics_route_baseline SET ewma_mean = 0, consecutive_low = 2 WHERE route_id = :r")
                .param("r", route)
                .update();

        recompute("13:30:00", "13:40:00");

        assertThat(baselines())
                .anySatisfy(row -> assertThat(row.get("open_episode_id")).isNotNull());
        assertThat(alerts()).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("resolved", false));
        runLive("14:40:10");
        assertThat(episodes()).isEqualTo(expectedEpisodes);
        assertThat(baselines()).isEqualTo(expectedBaselines);
        assertThat(alerts()).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("resolved", true));
    }

    @Test
    void theReplayPlanLooksOneWindowBackFromTheReplayedRecords() {
        traffic();

        // The records of the replay start at 13:34; their arrivals are up to ten minutes older.
        List<WorkItem> items = service()
                .plan(ReplaySource.GTFS_RT_TRIP_UPDATE, new ReplayRange(at("13:34:00"), at("13:36:00"), null, null));

        assertThat(items).extracting(WorkItem::scope).containsExactly(route);
        assertThat(items.getFirst().from()).isEqualTo(at("13:24:00"));
        assertThat(items.getFirst().to()).isEqualTo(at("13:36:00"));
    }

    /** A business clock that the test moves by hand. */
    private static final class ManualClock extends Clock {

        private Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
