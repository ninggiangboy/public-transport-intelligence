package dev.pti.analytics.bunching.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingEpisodeWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingStateStore;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader;
import dev.pti.analytics.bunching.adapter.out.metrics.MicrometerBunchingMetrics;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The bunching use case on the migrated warehouse as {@code etl_writer} (DOC-23 §18.8): real adapters, real lock, real
 * statements. Two vehicles run a ten-stop trip at 500 m a minute, {@code L} from 12:00:00 and {@code F} from 12:02:00:
 * a bunched pair against a headway of 600 s. The schedule is a stand-in; the etl-level test in {@code etl} runs the
 * same through the mini GTFS feed.
 */
class BunchingDetectorIT {

    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private static final double LAT0 = 44.9;
    private static final double METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate template = new JdbcTemplate(db.dataSource);
    private final String route = "AN2-" + UUID.randomUUID().toString().substring(0, 8);
    private final String trip = route + "-trip";
    private final ManualClock manual = new ManualClock(at("12:05:10"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final JdbcBunchingStateStore store = new JdbcBunchingStateStore(db.jdbc);
    private final JdbcAdvisoryLock lock = new JdbcAdvisoryLock(db.jdbc);
    private BunchingDetector detector;

    @BeforeEach
    void detector() {
        Reference reference = new Reference(route, trip);
        detector = new BunchingDetector(
                store,
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

    @AfterEach
    void cleanUp() {
        holder.shutdownNow();
        db.jdbc
                .sql("DELETE FROM dw.fact_vehicle_position WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM ops.alert_event WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM insight.insight_bus_bunching WHERE route_id = :r")
                .param("r", route)
                .update();
    }

    private static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    private static double lat(double progress) {
        return LAT0 + progress / METERS_PER_DEGREE;
    }

    /** Inserts the positions every 5 s of a vehicle that left the first stop at {@code start}. */
    private void drive(String vehicle, String start, String from, String to) {
        List<Object[]> rows = new ArrayList<>();
        Instant begin = at(start);
        for (Instant t = at(from); !t.isAfter(at(to)); t = t.plusSeconds(5)) {
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

    private void bunchedPair(String until) {
        drive("L", "12:00:00", "12:00:00", until);
        drive("F", "12:02:00", "12:02:00", until);
    }

    private RunResult afterBatch() {
        RunContext context = RunContext.afterBatch(
                RunResult.newBatchId(), UUID.randomUUID(), at("12:03:00"), at("12:05:00"), at("12:05:01"));
        return detector.advance(route, Trigger.BATCH, context);
    }

    private RunResult tick() {
        return detector.advance(route, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));
    }

    private long count(String table) {
        return db.jdbc
                .sql("SELECT count(*) FROM " + table + " WHERE route_id = :r")
                .param("r", route)
                .query(Long.class)
                .single();
    }

    private static List<String> types(RunResult result) {
        return result.events().stream().map(InsightEvent::type).toList();
    }

    @Test
    void aBunchedPairGivesOneEpisodeOneAlertAndTheEventsOfTheOpening() {
        bunchedPair("12:05:00");

        RunResult result = afterBatch();

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(types(result)).containsExactly("bunching.opened", "alert.created");
        assertThat(count("insight.insight_bus_bunching")).isEqualTo(1);
        assertThat(count("ops.alert_event")).isEqualTo(1);
        Map<String, Object> row = db.jdbc
                .sql("SELECT * FROM insight.insight_bus_bunching WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("status", "OPEN")
                .containsEntry("vehicle_leader", "L")
                .containsEntry("vehicle_follower", "F")
                .containsEntry("scheduled_headway_seconds", 600)
                .containsEntry("threshold_seconds", 300)
                .containsEntry("open_stop_id", "S2")
                .containsEntry("batch_id", result.batchId());
        assertThat(((Timestamp) row.get("episode_start")).toInstant()).isEqualTo(at("12:03:15"));
        assertThat(row.get("close_reason")).isNull();
        Map<String, Object> alert = db.jdbc
                .sql("SELECT type, severity, audience, ref_table, dedup_key, resolved_at FROM ops.alert_event"
                        + " WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        assertThat(alert)
                .containsEntry("type", "BUNCHING")
                .containsEntry("audience", "OPERATIONS")
                .containsEntry("ref_table", "insight.insight_bus_bunching")
                .containsEntry("dedup_key", "bunching:" + row.get("id"));
        assertThat(((Number) alert.get("severity")).intValue()).isEqualTo(1);
        assertThat(alert.get("resolved_at")).isNull();
        assertThat(count("insight.analytics_bunching_pair_state")).isEqualTo(1);
        assertThat(store.cursor(route)).contains(at("12:04:45"));
        assertThat(detector.routesNeedingTick()).contains(route);
        assertThat(meters.get("pti.analytics.episodes")
                        .tag("event", "opened")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void runningTheSameDataAgainAddsNoRowAndNoEvent() {
        bunchedPair("12:05:00");
        afterBatch();
        Map<String, Object> before = db.jdbc
                .sql("SELECT id, evaluation_count, min_gap_seconds, last_gap_seconds, last_evaluated_at"
                        + " FROM insight.insight_bus_bunching WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        // The operator's way to run the detector over the same data again: cursor and pair states go, rows stay.
        db.jdbc
                .sql("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = :r")
                .param("r", route)
                .update();
        db.jdbc
                .sql("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = :r")
                .param("r", route)
                .update();

        RunResult again = afterBatch();

        assertThat(again.outcome()).isEqualTo(Outcome.OK);
        assertThat(again.events()).isEmpty();
        assertThat(again.opened()).isZero();
        assertThat(count("insight.insight_bus_bunching")).isEqualTo(1);
        assertThat(count("ops.alert_event")).isEqualTo(1);
        Map<String, Object> after = db.jdbc
                .sql("SELECT id, evaluation_count, min_gap_seconds, last_gap_seconds, last_evaluated_at"
                        + " FROM insight.insight_bus_bunching WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        assertThat(after).isEqualTo(before);
        assertThat(tick().outcome()).as("the cursor is back at the watermark").isEqualTo(Outcome.NOOP);
    }

    @Test
    void theEpisodeClosesWhenTheSignalIsLostAndItsAlertIsResolved() {
        bunchedPair("12:05:00");
        afterBatch();
        manual.set(at("12:08:10"));

        RunResult result = tick();

        assertThat(types(result)).containsExactly("bunching.closed", "alert.updated");
        Map<String, Object> row = db.jdbc
                .sql("SELECT status, close_reason, episode_end FROM insight.insight_bus_bunching WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        assertThat(row).containsEntry("status", "CLOSED").containsEntry("close_reason", "SIGNAL_LOST");
        assertThat(((Timestamp) row.get("episode_end")).toInstant()).isEqualTo(at("12:06:45"));
        assertThat(db.jdbc
                        .sql("SELECT resolved_at IS NOT NULL FROM ops.alert_event WHERE route_id = :r")
                        .param("r", route)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(count("insight.analytics_bunching_pair_state")).isZero();
        assertThat(detector.routesNeedingTick()).doesNotContain(route);
        assertThat(count("insight.insight_bus_bunching")).isEqualTo(1);
        assertThat(meters.get("pti.analytics.episodes")
                        .tag("event", "closed")
                        .tag("reason", "signal_lost")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theEnrichmentColumnsAreNeverOverwritten() {
        bunchedPair("12:05:00");
        afterBatch();
        // Triage has enriched the episode while it is open.
        db.jdbc
                .sql("UPDATE insight.insight_bus_bunching SET enrichment_status = 'DONE', enrichment_attempts = 2"
                        + " WHERE route_id = :r")
                .param("r", route)
                .update();
        manual.set(at("12:08:10"));

        tick();

        Map<String, Object> row = db.jdbc
                .sql("SELECT enrichment_status, enrichment_attempts, status FROM insight.insight_bus_bunching"
                        + " WHERE route_id = :r")
                .param("r", route)
                .query()
                .singleRow();
        assertThat(row).containsEntry("enrichment_status", "DONE").containsEntry("status", "CLOSED");
        assertThat(((Number) row.get("enrichment_attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void aRunWhileAnotherHoldsTheRoutesLockIsSkippedAndTheNextOneContinues() throws Exception {
        bunchedPair("12:05:00");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> holder1 = holder.submit(() -> db.tx.inNewTransaction(() -> {
            boolean acquired = lock.tryAcquire(LockNames.bunching(route));
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return acquired;
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        RunResult skipped = afterBatch();

        assertThat(skipped.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(skipped.events()).isEmpty();
        assertThat(count("insight.insight_bus_bunching")).isZero();
        assertThat(store.cursor(route)).isEmpty();
        release.countDown();
        assertThat(holder1.get(30, TimeUnit.SECONDS)).isTrue();

        assertThat(afterBatch().outcome()).isEqualTo(Outcome.OK);
        assertThat(count("insight.insight_bus_bunching")).isEqualTo(1);
    }

    @Test
    void aRouteFarBehindJumpsAheadAndCountsTheSkippedGridPointsThatHadData() {
        bunchedPair("12:20:00");
        manual.set(at("12:20:10"));
        db.jdbc
                .sql("INSERT INTO insight.analytics_bunching_cursor (route_id, last_tick) VALUES (:r, :t)")
                .param("r", route)
                .param("t", java.time.OffsetDateTime.ofInstant(at("11:59:45"), ZoneOffset.UTC))
                .update();

        RunResult result = tick();

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(meters.get("pti.analytics.skipped.ticks")
                        .tag("detector", "bunching")
                        .counter()
                        .count())
                .isEqualTo(20);
        assertThat(store.cursor(route)).contains(at("12:19:45"));
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
                    ? Optional.of(new RouteInfo(routeId, 3, "AN2", Map.of(0, "Northbound")))
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
