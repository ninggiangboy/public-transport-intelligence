package dev.pti.analytics.disruption;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.disruption.adapter.out.jdbc.JdbcDisruptionStore;
import dev.pti.analytics.disruption.adapter.out.metrics.MicrometerDisruptionMetrics;
import dev.pti.analytics.disruption.application.DisruptionDetector;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.events.Audience;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import dev.pti.db.MigratedDatabases;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The disruption detector against the migrated warehouse as {@code etl_writer} (DOC-23 §18.3, §18.8): real SQL, real
 * advisory lock and alert writer, a seeded warmed-up baseline ({@code bucket_count = 60}, §16) and a burst of delay
 * in {@code fact_trip_update}. The schedule data is a stand-in: the detector only asks for route types and labels.
 */
class DisruptionDetectorIT {

    private static final Instant T0 = Instant.parse("2026-09-29T12:00:00Z");
    /** The watermark is 13:00:44 then (newest arrival 13:00:54 minus the allowed lateness): buckets up to 13:00. */
    private static final String END = "2026-09-29T13:01:10Z";

    private static final Instant WHOLE_HOUR = Instant.parse("2026-09-29T13:00:00Z");

    private final WarehouseSupport db = new WarehouseSupport();
    private final String route = "AN3-" + UUID.randomUUID().toString().substring(0, 8);
    private final JdbcDisruptionStore store = new JdbcDisruptionStore(db.jdbc);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private int trips;

    @AfterEach
    void cleanUp() {
        holder.shutdownNow();
        for (String table : List.of(
                "dw.fact_trip_update",
                "insight.insight_service_disruption",
                "insight.analytics_route_baseline",
                "insight.analytics_baseline_snapshot",
                "ops.alert_event")) {
            db.jdbc
                    .sql("DELETE FROM " + table + " WHERE route_id = :route")
                    .param("route", route)
                    .update();
        }
    }

    /** A feed with one bus route, the route of the test. */
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
                    ? Optional.of(new RouteInfo(route, 3, "AN3", Map.of(0, "Northbound", 1, "Southbound")))
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

    private DisruptionDetector detectorAt(Instant now) {
        return new DisruptionDetector(
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
                new BusinessClock(Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO));
    }

    private RunResult advance(String at) {
        return detectorAt(Instant.parse(at))
                .advance(route, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));
    }

    /** The baseline of DOC-23 §16: warmed up with {@code bucket_count = 60}, at {@code T0}. */
    private void seedBaseline(int bucketCount) {
        for (int direction : new int[] {0, 1}) {
            store.saveBaseline(route, direction, new BaselineState(63, 891, bucketCount, T0, 0, 0, null));
        }
    }

    /** Six arrivals a minute from minute {@code from} to {@code to} after {@code T0}, all with {@code delay}. */
    private void traffic(int direction, int from, int to, int delay) {
        List<Object[]> rows = new ArrayList<>();
        for (int minute = from; minute < to; minute++) {
            for (int i = 0; i < 6; i++) {
                Instant at = T0.plus(Duration.ofMinutes(minute)).plusSeconds(i * 9L);
                rows.add(arrival(direction, "S" + i, at, delay, true, "SCHEDULED", delay));
            }
        }
        insert(rows);
    }

    private Object[] arrival(
            int direction, String stop, Instant at, int delay, boolean observed, String relationship, Integer stored) {
        return new Object[] {
            LocalDate.of(2026, 9, 29),
            "T" + trips++,
            1,
            route,
            direction,
            stop,
            "V1",
            relationship,
            Timestamp.from(at.minusSeconds(delay)),
            Timestamp.from(at),
            stored,
            observed,
            Timestamp.from(at.plusSeconds(1)),
            "0".repeat(64),
            UUID.randomUUID()
        };
    }

    private void insert(List<Object[]> rows) {
        new JdbcTemplate(db.dataSource).batchUpdate("""
                        INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id,
                          stop_id, vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, delay_seconds,
                          is_observed, event_timestamp, payload_hash, batch_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
    }

    private List<Map<String, Object>> episodes() {
        return db.jdbc
                .sql("SELECT * FROM insight.insight_service_disruption WHERE route_id = :route ORDER BY episode_start")
                .param("route", route)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> alerts() {
        return db.jdbc
                .sql("SELECT * FROM ops.alert_event WHERE route_id = :route")
                .param("route", route)
                .query()
                .listOfRows();
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime offset ? offset.toInstant() : ((Timestamp) timestamp).toInstant();
    }

    private static List<String> types(RunResult result) {
        return result.events().stream().map(InsightEvent::type).toList();
    }

    /** The northbound burst of the scenario: 12:10 to 12:30, then normal again until 13:00. */
    private void burstThenRecovery() {
        traffic(0, 0, 10, 60);
        traffic(0, 10, 30, 300);
        traffic(0, 30, 61, 60);
        traffic(1, 0, 61, 60);
    }

    @Test
    void aBurstOpensExactlyOneEpisodeForTheTargetDirectionWithAPublicAlert() {
        seedBaseline(60);
        burstThenRecovery();

        RunResult result = advance("2026-09-29T12:31:00Z");

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(episodes()).hasSize(1);
        Map<String, Object> episode = episodes().getFirst();
        assertThat(episode)
                .containsEntry("direction_id", 0)
                .containsEntry("status", "OPEN")
                .containsEntry("close_reason", null)
                .containsEntry("episode_end", null);
        assertThat(instant(episode.get("episode_start"))).isEqualTo(Instant.parse("2026-09-29T12:13:00Z"));
        assertThat(alerts()).hasSize(1);
        assertThat(alerts().getFirst())
                .containsEntry("type", "DISRUPTION")
                .containsEntry("audience", "PUBLIC")
                .containsEntry("ref_id", episode.get("id").toString())
                .containsEntry("dedup_key", "disruption:" + episode.get("id"));
        assertThat(types(result)).contains("disruption.opened", "alert.created");
        assertThat(result.events())
                .filteredOn(e -> e.type().startsWith("disruption."))
                .extracting(InsightEvent::audience)
                .containsOnly(Audience.PUBLIC);
        assertThat(store.baselines(route).get(0).openEpisodeId())
                .hasToString(episode.get("id").toString());
        assertThat(store.cursor(route)).contains(Instant.parse("2026-09-29T12:31:00Z"));
        assertThat(detectorAt(T0).routesNeedingTick()).contains(route);
        assertThat(meters.counter(
                                "pti.analytics.episodes", "detector", "disruption", "event", "opened", "reason", "none")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theRecoveryClosesTheEpisodeAndKeepsWhatTriageWrote() throws SQLException {
        seedBaseline(60);
        burstThenRecovery();
        advance("2026-09-29T12:31:00Z");
        UUID id = (UUID) episodes().getFirst().get("id");
        try (Connection owner = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement statement = owner.createStatement()) {
            statement.execute("""
                    UPDATE insight.insight_service_disruption
                    SET likely_cause = 'traffic', cause_confidence = 0.8, model_version = 'jev-test',
                        enriched_at = now(), enrichment_status = 'DONE'
                    WHERE id = '%s'""".formatted(id));
            statement.execute("UPDATE ops.alert_event SET audience = 'ENGINEERING' WHERE dedup_key = 'disruption:%s'"
                    .formatted(id));
        }

        RunResult result = advance(END);

        assertThat(episodes()).hasSize(1);
        Map<String, Object> episode = episodes().getFirst();
        assertThat(episode).containsEntry("status", "CLOSED").containsEntry("close_reason", "RECOVERED");
        assertThat(instant(episode.get("episode_end"))).isAfter(Instant.parse("2026-09-29T12:30:00Z"));
        assertThat(episode)
                .containsEntry("likely_cause", "traffic")
                .containsEntry("enrichment_status", "DONE")
                .containsEntry("model_version", "jev-test");
        assertThat(alerts().getFirst().get("resolved_at")).isNotNull();
        assertThat(types(result)).containsExactly("disruption.closed", "alert.updated");
        assertThat(result.events()).extracting(InsightEvent::audience).containsOnly(Audience.ENGINEERING);
        assertThat(store.baselines(route).get(0).openEpisodeId()).isNull();
        assertThat(meters.counter(
                                "pti.analytics.episodes",
                                "detector",
                                "disruption",
                                "event",
                                "closed",
                                "reason",
                                "RECOVERED")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void snapshotsAreWrittenAtWholeHoursForBothDirections() {
        seedBaseline(60);
        burstThenRecovery();
        advance("2026-09-29T12:31:00Z");
        advance(END);

        List<Map<String, Object>> snapshots = db.jdbc
                .sql("SELECT * FROM insight.analytics_baseline_snapshot WHERE route_id = :route ORDER BY direction_id")
                .param("route", route)
                .query()
                .listOfRows();

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).allSatisfy(snapshot -> {
            assertThat(instant(snapshot.get("snapshot_hour"))).isEqualTo(WHOLE_HOUR);
            assertThat(instant(snapshot.get("last_bucket"))).isEqualTo(WHOLE_HOUR);
        });
        BaselineState live = store.baselines(route).get(0);
        assertThat(snapshots.getFirst().get("ewma_mean")).isEqualTo(live.mean());
        assertThat(snapshots.getFirst().get("bucket_count")).isEqualTo(live.bucketCount());
    }

    @Test
    void noEpisodeOpensDuringTheWarmUp() {
        seedBaseline(10);
        burstThenRecovery();

        RunResult result = advance(END);

        assertThat(episodes()).isEmpty();
        assertThat(alerts()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(store.baselines(route).get(0).bucketCount()).isGreaterThan(10);
    }

    @Test
    void runningTheSameDataAgainAddsNoRowAndNoEvent() {
        seedBaseline(60);
        burstThenRecovery();
        advance("2026-09-29T12:31:00Z");
        advance(END);
        Map<String, Object> episodeBefore = episodes().getFirst();
        Map<String, Object> alertBefore = alerts().getFirst();
        // The cursor and the state go back; the rows stay, as they would after an operator reset.
        seedBaseline(60);

        RunResult result = advance(END);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.events()).isEmpty();
        assertThat(episodes()).hasSize(1);
        assertThat(episodes().getFirst().get("id")).isEqualTo(episodeBefore.get("id"));
        assertThat(episodes().getFirst()).containsEntry("status", "CLOSED");
        assertThat(alerts()).hasSize(1);
        assertThat(alerts().getFirst().get("resolved_at")).isEqualTo(alertBefore.get("resolved_at"));
    }

    @Test
    void aRunWithTheRouteLockHeldElsewhereIsSkipped() throws Exception {
        seedBaseline(60);
        burstThenRecovery();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> other = holder.submit(() -> db.tx.inNewTransaction(() -> {
            boolean acquired = new JdbcAdvisoryLock(db.jdbc).tryAcquire(LockNames.disruption(route));
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return acquired;
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        RunResult result = advance("2026-09-29T12:31:00Z");

        release.countDown();
        assertThat(other.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(episodes()).isEmpty();
        assertThat(store.cursor(route)).contains(T0);
        // The next trigger continues from the cursor.
        assertThat(advance("2026-09-29T12:31:00Z").outcome()).isEqualTo(Outcome.OK);
        assertThat(episodes()).hasSize(1);
    }

    @Test
    void onlyObservedScheduledArrivalsWithADelayAreRead() {
        Instant at = T0.plusSeconds(30);
        insert(List.of(
                arrival(0, "A", at, 100, true, "SCHEDULED", 100),
                arrival(0, "B", at, 100, false, "SCHEDULED", 100),
                arrival(0, "C", at, 100, true, "SCHEDULED", null),
                arrival(0, "D", at.plusSeconds(1), 100, true, "SKIPPED", 100),
                arrival(1, "E", at, -20, true, "SCHEDULED", -20)));
        var dates = ServiceDates.covering(T0, T0.plusSeconds(60), ZoneId.of("America/Chicago"));

        assertThat(store.arrivals(route, T0, T0.plusSeconds(60), dates))
                .extracting(a -> a.stopId() + a.directionId() + a.delaySeconds())
                .containsExactlyInAnyOrder("A0100", "E1-20");
        assertThat(store.arrivals(route, T0.plusSeconds(31), T0.plusSeconds(60), dates))
                .isEmpty();
        assertThat(store.hasArrivals(route, T0, T0.plusSeconds(60), dates)).isTrue();
        assertThat(store.hasArrivals(route, T0.plusSeconds(31), T0.plusSeconds(60), dates))
                .isFalse();
        assertThat(store.newestUpdate(route, dates)).contains(at.plusSeconds(2));
    }
}
