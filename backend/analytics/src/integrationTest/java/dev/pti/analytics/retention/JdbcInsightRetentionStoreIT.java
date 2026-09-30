package dev.pti.analytics.retention;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.retention.adapter.out.jdbc.JdbcInsightRetentionStore;
import dev.pti.analytics.retention.domain.RetentionCutoff;
import dev.pti.analytics.retention.domain.RetentionPolicy;
import dev.pti.analytics.retention.domain.RetentionTarget;
import dev.pti.db.MigratedDatabases;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The deletes of DOC-23 §12.3 against the migrated warehouse as {@code etl_writer} (AN-I-10): an old closed row goes, a
 * recent one stays, an open episode stays however old, and one batch deletes at most its limit. Rows are seeded as the
 * owner, because {@code etl_writer} may delete suggestions but not insert them (DOC-17 §4.1).
 */
class JdbcInsightRetentionStoreIT {

    private static final RetentionPolicy POLICY =
            new RetentionPolicy(Duration.ofDays(365), Duration.ofDays(30), ZoneId.of("America/Chicago"));
    private static final Instant NOW = Instant.parse("2026-09-30T18:00:00Z");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcInsightRetentionStore store = new JdbcInsightRetentionStore(db.jdbc);
    private final String route = "AN4-" + UUID.randomUUID().toString().substring(0, 8);
    private final String oldTs = "TIMESTAMPTZ '2025-09-01 00:00:00Z'";
    private final String recentTs = "TIMESTAMPTZ '2026-09-01 00:00:00Z'";

    @AfterEach
    void cleanUp() {
        owner("DELETE FROM insight.insight_bus_bunching WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.insight_service_disruption WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.insight_ticketing_anomaly WHERE sale_point_id = '%s'".formatted(route));
        owner("DELETE FROM insight.insight_otp_scorecard WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.insight_dispatch_suggestion WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.analytics_baseline_snapshot WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = '%s'".formatted(route));
        owner("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = '%s'".formatted(route));
    }

    private static void owner(String sql) {
        try (Connection c = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int purge(RetentionTarget target, int limit) {
        RetentionCutoff cutoff = POLICY.cutoff(target, NOW, NOW);
        return db.tx.inTransaction(() -> store.deleteExpired(target, cutoff, limit));
    }

    private long count(String table, String where) {
        return db.jdbc
                .sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Long.class)
                .single();
    }

    private void bunching(String leader, String start, String status, String end) {
        owner("""
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, episode_end, status, close_reason,
                  scheduled_headway_seconds, threshold_seconds, min_gap_seconds, last_gap_seconds, last_evaluated_at,
                  batch_id)
                VALUES (gen_random_uuid(), '%s', 0, '%s', 'F', 't1', 't2', %s, %s, '%s', %s, 600, 300, 100, 100, %s,
                  gen_random_uuid())""".formatted(
                route, leader, start, end, status, status.equals("CLOSED") ? "'GAP_RECOVERED'" : "NULL", start));
    }

    @Test
    void anI10AClosedEpisodeOlderThanAYearGoesAnOpenOneAndARecentOneStay() {
        bunching("old-closed", oldTs, "CLOSED", "TIMESTAMPTZ '2025-09-01 00:10:00Z'");
        bunching("old-open", oldTs, "OPEN", "NULL");
        bunching("recent-closed", recentTs, "CLOSED", "TIMESTAMPTZ '2026-09-01 00:10:00Z'");

        assertThat(purge(RetentionTarget.BUNCHING, 100)).isGreaterThanOrEqualTo(1);

        assertThat(count(
                        "insight.insight_bus_bunching",
                        "route_id = '%s' AND vehicle_leader = 'old-closed'".formatted(route)))
                .isZero();
        assertThat(count(
                        "insight.insight_bus_bunching",
                        "route_id = '%s' AND vehicle_leader = 'old-open'".formatted(route)))
                .isEqualTo(1);
        assertThat(count(
                        "insight.insight_bus_bunching",
                        "route_id = '%s' AND vehicle_leader = 'recent-closed'".formatted(route)))
                .isEqualTo(1);
    }

    @Test
    void disruptionEpisodesFollowTheSameRule() {
        for (String[] e : new String[][] {
            {oldTs, "CLOSED", "TIMESTAMPTZ '2025-09-01 00:30:00Z'"},
            {"TIMESTAMPTZ '2025-09-02 00:00:00Z'", "OPEN", "NULL"},
            {recentTs, "CLOSED", "TIMESTAMPTZ '2026-09-01 00:30:00Z'"}
        }) {
            owner("""
                    INSERT INTO insight.insight_service_disruption (id, route_id, direction_id, episode_start,
                      episode_end, status, close_reason, baseline_mean_seconds, baseline_stddev_seconds,
                      current_avg_delay_seconds, current_z_score, peak_avg_delay_seconds, peak_z_score, sample_count,
                      last_bucket, batch_id)
                    VALUES (gen_random_uuid(), '%s', 0, %s, %s, '%s', %s, 60, 30, 200, 4, 210, 5, 20, %s,
                      gen_random_uuid())""".formatted(route, e[0], e[2], e[1], e[1].equals("CLOSED") ? "'RECOVERED'" : "NULL", e[0]));
        }

        purge(RetentionTarget.DISRUPTION, 100);

        assertThat(count("insight.insight_service_disruption", "route_id = '%s'".formatted(route)))
                .isEqualTo(2);
        assertThat(count("insight.insight_service_disruption", "route_id = '%s' AND status = 'OPEN'".formatted(route)))
                .isEqualTo(1);
    }

    @Test
    void anAnomalyExpiresByDetectedAt() {
        for (String[] w : new String[][] {
            {"2025-09-01 00:00:00Z", "2025-09-01 00:15:00Z"}, {"2026-09-01 00:00:00Z", "2026-09-01 00:15:00Z"}
        }) {
            owner("""
                    INSERT INTO insight.insight_ticketing_anomaly (id, sale_point_id, window_start, window_end, trigger,
                      txn_count, refund_count, refund_ratio, amount_sum, summary, detected_at, batch_id)
                    VALUES (gen_random_uuid(), '%s', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s', 'VOLUME', 30, 0, 0, 10, '{}',
                      TIMESTAMPTZ '%s', gen_random_uuid())""".formatted(route, w[0], w[1], w[1]));
        }

        purge(RetentionTarget.TICKETING_ANOMALY, 100);

        assertThat(count("insight.insight_ticketing_anomaly", "sale_point_id = '%s'".formatted(route)))
                .isEqualTo(1);
    }

    @Test
    void aScorecardExpiresByServiceDateAgainstTheLocalDate() {
        LocalDate today = LocalDate.parse("2026-09-30");
        for (LocalDate d : List.of(today.minusDays(366), today.minusDays(365), today.minusDays(1))) {
            owner("""
                    INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage, on_time_count,
                      early_count, late_count, observation_count, trip_count, early_tolerance_seconds,
                      late_tolerance_seconds, computed_at, batch_id)
                    VALUES ('%s', DATE '%s', 100, 1, 0, 0, 1, 1, 300, 300, now(), gen_random_uuid())""".formatted(route, d));
        }

        purge(RetentionTarget.OTP_SCORECARD, 100);

        assertThat(db.jdbc
                        .sql("SELECT service_date FROM insight.insight_otp_scorecard WHERE route_id = :r ORDER BY 1")
                        .param("r", route)
                        .query(LocalDate.class)
                        .list())
                .as("strictly older than today − 365 goes; the day on the line stays")
                .containsExactly(today.minusDays(365), today.minusDays(1));
    }

    @Test
    void aSuggestionExpiresByCreatedAtAndTheEtlRoleMayDeleteIt() {
        owner("""
                INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
                  state_snapshot, model_version, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), '%s', 'no_action', 0.5, '{}', 't', %s),
                       (gen_random_uuid(), gen_random_uuid(), '%s', 'no_action', 0.5, '{}', 't', %s)""".formatted(route, oldTs, route, recentTs));

        assertThat(purge(RetentionTarget.DISPATCH_SUGGESTION, 100)).isGreaterThanOrEqualTo(1);

        assertThat(count("insight.insight_dispatch_suggestion", "route_id = '%s'".formatted(route)))
                .isEqualTo(1);
    }

    @Test
    void aSnapshotExpiresAfterThirtyDays() {
        for (String hour : List.of("2026-08-01 00:00:00Z", "2026-09-29 00:00:00Z")) {
            owner("""
                    INSERT INTO insight.analytics_baseline_snapshot (snapshot_hour, route_id, direction_id, ewma_mean,
                      ewma_var, bucket_count, last_bucket, consecutive_high, consecutive_low)
                    VALUES (TIMESTAMPTZ '%s', '%s', 0, 60, 900, 61, TIMESTAMPTZ '%s', 0, 0)""".formatted(hour, route, hour));
        }

        purge(RetentionTarget.BASELINE_SNAPSHOT, 100);

        assertThat(count("insight.analytics_baseline_snapshot", "route_id = '%s'".formatted(route)))
                .isEqualTo(1);
    }

    @Test
    void aCursorOlderThanAWeekGoesUnlessItsRouteStillHoldsAPairState() {
        String busy = route + "-busy";
        owner("""
                INSERT INTO insight.analytics_bunching_cursor (route_id, last_tick)
                VALUES ('%s', TIMESTAMPTZ '2026-09-01 00:00:00Z'), ('%s', TIMESTAMPTZ '2026-09-01 00:00:00Z'),
                       ('%s-new', TIMESTAMPTZ '2026-09-30 17:00:00Z')""".formatted(route, busy, route));
        owner("""
                INSERT INTO insight.analytics_bunching_pair_state (route_id, direction_id, vehicle_leader,
                  vehicle_follower, trip_leader, trip_follower, last_evaluated_at)
                VALUES ('%s', 0, 'L', 'F', 't1', 't2', TIMESTAMPTZ '2026-09-01 00:00:00Z')""".formatted(busy));
        try {
            purge(RetentionTarget.BUNCHING_CURSOR, 100);

            assertThat(count("insight.analytics_bunching_cursor", "route_id = '%s'".formatted(route)))
                    .isZero();
            assertThat(count("insight.analytics_bunching_cursor", "route_id = '%s'".formatted(busy)))
                    .isEqualTo(1);
            assertThat(count("insight.analytics_bunching_cursor", "route_id = '%s-new'".formatted(route)))
                    .isEqualTo(1);
        } finally {
            owner("DELETE FROM insight.analytics_bunching_cursor WHERE route_id IN ('%s-busy', '%s-new')"
                    .formatted(route, route));
            owner("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = '%s'".formatted(busy));
        }
    }

    @Test
    void oneCallDeletesNoMoreThanItsLimit() {
        for (int i = 0; i < 5; i++) {
            bunching(
                    "old-" + i,
                    oldTs.replace("00:00:00", "00:0" + i + ":00"),
                    "CLOSED",
                    "TIMESTAMPTZ '2025-09-02 00:00:00Z'");
        }

        int total = 0;
        int deleted;
        do {
            deleted = purge(RetentionTarget.BUNCHING, 2);
            assertThat(deleted).as("never more than the limit").isLessThanOrEqualTo(2);
            total += deleted;
        } while (deleted == 2);

        assertThat(total).isGreaterThanOrEqualTo(5);
        assertThat(count("insight.insight_bus_bunching", "route_id = '%s'".formatted(route)))
                .isZero();
    }
}
