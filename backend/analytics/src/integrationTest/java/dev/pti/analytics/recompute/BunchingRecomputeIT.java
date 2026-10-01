package dev.pti.analytics.recompute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingEpisodeWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingStateStore;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader;
import dev.pti.analytics.bunching.adapter.out.metrics.MicrometerBunchingMetrics;
import dev.pti.analytics.bunching.application.BunchingDetector;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcBunchingRecomputeStore;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcRecomputeScopes;
import dev.pti.analytics.recompute.adapter.out.metrics.MicrometerRecomputeMetrics;
import dev.pti.analytics.recompute.application.BunchingRecompute;
import dev.pti.analytics.recompute.application.DetectorRecomputeService;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import dev.pti.db.MigratedDatabases;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The bunching recompute against the migrated warehouse (DOC-23 §18.7, AN-R-01…AN-R-05 and AN-R-08). Four bunched
 * pairs run a ten-stop trip at 500 m a minute in two hours, half an hour apart; the live detector runs over them in
 * ten-minute steps, and the recompute has to give the rows it gave, with the same ids.
 */
class BunchingRecomputeIT {

    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private static final double LAT0 = 44.9;
    private static final double METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final List<String> PAIR_STARTS = List.of("12:00:00", "12:30:00", "13:00:00", "13:30:00");

    private static final String EPISODE_COLUMNS = """
            id, route_id, direction_id, vehicle_leader, vehicle_follower, trip_leader, trip_follower, episode_start,
            episode_end, status, close_reason, scheduled_headway_seconds, threshold_seconds, min_gap_seconds,
            last_gap_seconds, open_stop_id, evaluation_count, last_evaluated_at""";

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate template = new JdbcTemplate(db.dataSource);
    private final String route = "RC1-" + UUID.randomUUID().toString().substring(0, 8);
    private final String trip = route + "-trip";
    private final ManualClock manual = new ManualClock(at("12:00:10"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final JdbcBunchingStateStore state = new JdbcBunchingStateStore(db.jdbc);
    private final JdbcAdvisoryLock lock = new JdbcAdvisoryLock(db.jdbc);
    private final Reference reference = new Reference(route, trip);
    private final BunchingDetector detector = detector();

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        db.jdbc
                .sql("DELETE FROM insight.insight_dispatch_suggestion WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM dw.fact_vehicle_position WHERE route_id = :r")
                .param("r", route)
                .update();
        resetInsight();
    }

    private BunchingDetector detector() {
        return new BunchingDetector(
                state,
                new JdbcVehicleHistoryReader(db.jdbc),
                new JdbcBunchingEpisodeWriter(db.jdbc),
                new JdbcAlertWriter(db.jdbc),
                reference,
                lock,
                new JdbcTransactionLimits(db.jdbc),
                new MicrometerAnalyticsMetrics(meters),
                new MicrometerBunchingMetrics(meters),
                db.tx,
                new BusinessClock(manual, Duration.ZERO),
                AnalyticsPropertiesFixtures.defaults().bunching().toThresholds());
    }

    private DetectorRecomputeService service(AdvisoryLock recomputeLock) {
        BunchingRecompute recompute = new BunchingRecompute(
                AnalyticsPropertiesFixtures.defaults().bunching().toThresholds(),
                state,
                new JdbcVehicleHistoryReader(db.jdbc),
                new JdbcBunchingEpisodeWriter(db.jdbc),
                new JdbcAlertWriter(db.jdbc),
                new JdbcBunchingRecomputeStore(db.jdbc),
                new JdbcRecomputeScopes(db.jdbc),
                reference,
                recomputeLock,
                new JdbcTransactionLimits(db.jdbc),
                db.tx,
                new RunReporter(new MicrometerAnalyticsMetrics(meters)),
                new BusinessClock(manual, Duration.ZERO));
        return new DetectorRecomputeService(List.of(recompute), new MicrometerRecomputeMetrics(meters));
    }

    private DetectorRecomputeService service() {
        return service(lock);
    }

    private static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    private static double lat(double progress) {
        return LAT0 + progress / METERS_PER_DEGREE;
    }

    /** Positions every 5 s of a vehicle that left the first stop at {@code start}, until {@code until}. */
    private void drive(String vehicle, String start, String until) {
        List<Object[]> rows = new ArrayList<>();
        Instant begin = at(start);
        for (Instant t = begin; !t.isAfter(at(until)); t = t.plusSeconds(5)) {
            double progress = Math.min(4500, 500.0 * Duration.between(begin, t).getSeconds() / 60);
            int stop = (int) Math.ceil(progress / 500);
            rows.add(new Object[] {
                DAY,
                vehicle,
                Timestamp.from(t),
                trip,
                route,
                lat(progress),
                -93.27,
                stop + 1,
                "S" + stop,
                progress % 500 == 0 ? "STOPPED_AT" : "IN_TRANSIT_TO",
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID()
            });
        }
        template.batchUpdate("""
                INSERT INTO dw.fact_vehicle_position (service_date, vehicle_id, event_timestamp, trip_id, route_id,
                  direction_id, lat, lon, current_stop_sequence, stop_id, current_status, schema_version, payload_hash,
                  batch_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1, ?, ?)""", rows);
    }

    /** Four pairs, a leader and a follower two minutes behind, each on the road for twelve minutes. */
    private void fixture() {
        for (int k = 0; k < PAIR_STARTS.size(); k++) {
            Instant start = at(PAIR_STARTS.get(k));
            drive("L" + k, PAIR_STARTS.get(k), time(start.plusSeconds(12 * 60)));
            drive("F" + k, time(start.plusSeconds(120)), time(start.plusSeconds(14 * 60)));
        }
    }

    private static String time(Instant instant) {
        return instant.toString().substring(11, 19);
    }

    /** The live detector over the fixture: the clock moves ten minutes at a time, as micro-batches would have. */
    private void runLive(String from, String until) {
        Instant now = at(from);
        while (now.isBefore(at(until))) {
            liveAt(now);
            now = now.plus(Duration.ofMinutes(10));
        }
        liveAt(at(until));
    }

    private void liveAt(Instant now) {
        manual.set(now);
        detector.advance(
                route,
                Trigger.BATCH,
                RunContext.afterBatch(RunResult.newBatchId(), UUID.randomUUID(), at("12:00:00"), now, now));
    }

    private void resetInsight() {
        for (String table : List.of(
                "insight.insight_bus_bunching",
                "insight.analytics_bunching_pair_state",
                "insight.analytics_bunching_cursor",
                "ops.alert_event")) {
            db.jdbc
                    .sql("DELETE FROM " + table + " WHERE route_id = :r")
                    .param("r", route)
                    .update();
        }
    }

    private List<Map<String, Object>> episodes() {
        return db.jdbc
                .sql("SELECT " + EPISODE_COLUMNS + " FROM insight.insight_bus_bunching WHERE route_id = :r"
                        + " ORDER BY episode_start, vehicle_leader")
                .param("r", route)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> pairStates() {
        return db.jdbc
                .sql("SELECT * FROM insight.analytics_bunching_pair_state WHERE route_id = :r"
                        + " ORDER BY vehicle_leader, vehicle_follower")
                .param("r", route)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> alerts() {
        return db.jdbc
                .sql(
                        "SELECT dedup_key, severity, audience, title, CAST(body AS text) AS body, resolved_at IS NOT NULL AS resolved"
                                + " FROM ops.alert_event WHERE route_id = :r ORDER BY dedup_key")
                .param("r", route)
                .query()
                .listOfRows();
    }

    private DetectorStats recompute(String from, String to) {
        DetectorRecomputeService service = service();
        List<WorkItem> items = service.plan(Set.of(Detector.BUNCHING), at(from), at(to));
        assertThat(items).extracting(WorkItem::scope).containsExactly(route);
        return service.execute(items.getFirst());
    }

    @Test
    void recomputingOverDataTheLivePathProcessedGivesTheSameRowsWithTheSameIds() {
        fixture();
        runLive("12:00:10", "14:10:10");
        List<Map<String, Object>> expected = episodes();
        assertThat(expected).as("one episode for each bunched pair").hasSize(4);
        resetInsight();

        DetectorStats stats = recompute("12:00:00", "14:00:00");

        assertThat(episodes()).isEqualTo(expected);
        assertThat(stats).isEqualTo(new DetectorStats(1, 4, 0));
        assertThat(alerts())
                .as("a recompute opens no alert for an episode that is closed")
                .isEmpty();
        assertThat(pairStates())
                .as("the walk ended quiet, so there is nothing to hand over")
                .isEmpty();
        assertThat(state.cursor(route)).as("and the cursor is untouched").isEmpty();
        assertThat(meters.get("pti.analytics.recompute.rows")
                        .tag("detector", "bunching")
                        .tag("op", "upserted")
                        .counter()
                        .count())
                .isEqualTo(4);
        assertThat(meters.get("pti.analytics.runs")
                        .tag("detector", "bunching")
                        .tag("trigger", "recompute")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aRecomputeOverRowsThatAlreadyExistRewritesThemUnchangedAndKeepsTheirAlerts() {
        fixture();
        runLive("12:00:10", "14:10:10");
        List<Map<String, Object>> expected = episodes();
        List<Map<String, Object>> expectedAlerts = alerts();

        DetectorStats stats = recompute("12:00:00", "14:00:00");

        assertThat(episodes()).isEqualTo(expected);
        assertThat(alerts()).isEqualTo(expectedAlerts);
        assertThat(stats.upserted()).isEqualTo(4);
        assertThat(stats.deleted()).isZero();
    }

    @Test
    void aRangeThatCutsAnEpisodeInHalfRewritesTheEpisodeWholeWithItsId() {
        fixture();
        runLive("12:00:10", "14:10:10");
        List<Map<String, Object>> expected = episodes();
        // Damage the first episode: if the recompute started inside it, it would never be repaired.
        db.jdbc
                .sql("UPDATE insight.insight_bus_bunching SET min_gap_seconds = 1, evaluation_count = 1"
                        + " WHERE id = :id")
                .param("id", expected.getFirst().get("id"))
                .update();

        DetectorStats stats = recompute("12:06:00", "12:07:00");

        assertThat(episodes()).isEqualTo(expected);
        assertThat(stats.upserted()).isEqualTo(1);
        assertThat(stats.deleted()).isZero();
    }

    @Test
    void anEpisodeWithoutSupportInThePositionsIsDeletedAndItsAlertIsWithdrawn() {
        fixture();
        runLive("12:00:10", "14:10:10");
        List<Map<String, Object>> before = episodes();
        UUID gone = (UUID) before.get(2).get("id");
        // The positions of the follower of the third pair are removed: it is no longer on the road.
        db.jdbc
                .sql("DELETE FROM dw.fact_vehicle_position WHERE route_id = :r AND vehicle_id = 'F2'")
                .param("r", route)
                .update();

        DetectorStats stats = recompute("12:00:00", "14:00:00");

        assertThat(stats).isEqualTo(new DetectorStats(1, 3, 1));
        assertThat(episodes())
                .extracting(row -> row.get("id"))
                .doesNotContain(gone)
                .hasSize(3);
        Map<String, Object> alert = alerts().stream()
                .filter(row -> row.get("dedup_key").equals("bunching:" + gone))
                .findFirst()
                .orElseThrow();
        assertThat(alert).containsEntry("resolved", true);
        assertThat((String) alert.get("body")).contains("\"withdrawn\": true");
        assertThat(alerts().stream().filter(row -> !row.get("dedup_key").equals("bunching:" + gone)))
                .as("the alerts of the episodes that were reproduced are unchanged")
                .allSatisfy(row -> assertThat((String) row.get("body")).doesNotContain("withdrawn"));
        assertThat(meters.get("pti.analytics.recompute.rows")
                        .tag("detector", "bunching")
                        .tag("op", "deleted")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aRangeThatReachesTheLiveCursorHandsOverThePairStateAndTheLiveRunContinuesWithoutDuplicates() {
        fixture();
        runLive("12:00:10", "14:10:10");
        List<Map<String, Object>> expected = episodes();
        resetInsight();
        // The live run stops at 13:06, with the third pair's episode open: it began at 13:03:15.
        runLive("12:00:10", "13:06:10");
        assertThat(state.cursor(route)).contains(at("13:06:00"));
        List<Map<String, Object>> openBefore = pairStates();
        assertThat(openBefore).isNotEmpty();
        // The state of the live detector is lost: the open episode row and the pair states.
        db.jdbc
                .sql("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM insight.insight_bus_bunching WHERE route_id = :r AND status = 'OPEN'")
                .param("r", route)
                .update();

        recompute("13:00:00", "13:06:00");

        assertThat(pairStates())
                .as("the state of the walk replaces the live one")
                .isEqualTo(openBefore);
        assertThat(episodes()).anyMatch(row -> row.get("status").equals("OPEN"));
        assertThat(state.cursor(route)).as("the live cursor is where it was").contains(at("13:06:00"));
        runLive("13:16:10", "14:10:10");
        assertThat(episodes()).isEqualTo(expected);
        assertThat(alerts())
                .extracting(row -> row.get("dedup_key"))
                .doesNotHaveDuplicates()
                .hasSize(4);
        assertThat(alerts()).allSatisfy(row -> assertThat(row).containsEntry("resolved", true));
    }

    @Test
    void aLiveRunWhileTheRecomputeHoldsTheRoutesLockIsSkippedAndRunsAgainAfterwards() throws Exception {
        fixture();
        manual.set(at("14:05:10"));
        List<RunResult> duringRecompute = new ArrayList<>();
        AdvisoryLock observing = new AdvisoryLock() {
            @Override
            public boolean tryAcquire(String lockName) {
                return lock.tryAcquire(lockName);
            }

            @Override
            public void acquire(String lockName, Duration lockTimeout) {
                lock.acquire(lockName, lockTimeout);
                try {
                    // The recompute holds the lock now; the live detector runs on another thread, as it would.
                    duringRecompute.add(executor.submit(() -> detector.advance(
                                    route, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId())))
                            .get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        DetectorRecomputeService service = service(observing);
        List<WorkItem> items = service.plan(Set.of(Detector.BUNCHING), at("12:00:00"), at("14:00:00"));

        service.execute(items.getFirst());

        assertThat(duringRecompute).extracting(RunResult::outcome).containsExactly(Outcome.SKIPPED_LOCKED);
        assertThat(state.cursor(route)).as("the live run did nothing").isEmpty();
        RunResult caughtUp = detector.advance(route, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));
        assertThat(caughtUp.outcome()).isEqualTo(Outcome.OK);
        assertThat(state.cursor(route)).isPresent();
    }

    @Test
    void theEnrichmentOfAReproducedEpisodeAndItsDispatchSuggestionAreKept() throws SQLException {
        fixture();
        runLive("12:00:10", "14:10:10");
        UUID id = (UUID) episodes().getFirst().get("id");
        db.jdbc
                .sql("UPDATE insight.insight_bus_bunching SET enrichment_status = 'DONE', enrichment_attempts = 2"
                        + " WHERE id = :id")
                .param("id", id)
                .update();
        try (Connection triage = MigratedDatabases.connect("pti_warehouse", "triage_writer")) {
            try (var statement = triage.prepareStatement("""
                    INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action,
                      action_confidence, state_snapshot, model_version)
                    VALUES (?, ?, ?, 'hold_follower', 0.9, '{}'::jsonb, 'test')""")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, id);
                statement.setString(3, route);
                statement.executeUpdate();
            }
        }

        recompute("12:00:00", "14:00:00");

        Map<String, Object> row = db.jdbc
                .sql("SELECT enrichment_status, enrichment_attempts FROM insight.insight_bus_bunching WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        assertThat(row).containsEntry("enrichment_status", "DONE");
        assertThat(((Number) row.get("enrichment_attempts")).intValue()).isEqualTo(2);
        assertThat(db.jdbc
                        .sql("SELECT count(*) FROM insight.insight_dispatch_suggestion WHERE bunching_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aRouteWithNothingInTheRangeGetsNoItem() {
        fixture();

        List<WorkItem> items = service().plan(Set.of(Detector.BUNCHING), at("09:00:00"), at("10:00:00"));

        assertThat(items).isEmpty();
    }

    /** A feed with one bus route and the ten-stop trip, headway 600 s for every hour. */
    private static final class Reference implements AnalyticsReferenceCache {

        private final String routeId;
        private final TripPattern pattern;

        Reference(String routeId, String tripId) {
            this.routeId = routeId;
            List<PatternStop> stops = new ArrayList<>();
            for (int k = 0; k < 10; k++) {
                int seconds = 12 * 3600 + 60 * k;
                stops.add(new PatternStop(k + 1, "S" + k, seconds, seconds, 500.0 * k, lat(500.0 * k), -93.27));
            }
            this.pattern = new TripPattern(tripId, routeId, 0, stops);
        }

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
        public Optional<RouteInfo> route(String id) {
            return id.equals(routeId)
                    ? Optional.of(new RouteInfo(routeId, 3, "RC1", Map.of(0, "Northbound")))
                    : Optional.empty();
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return tripId.equals(pattern.tripId()) ? Optional.of(pattern) : Optional.empty();
        }

        @Override
        public OptionalInt scheduledHeadway(String id, int directionId, DayType dayType, int hourOfServiceDay) {
            return OptionalInt.of(600);
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.WEEKDAY;
        }
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
