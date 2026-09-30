package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V7__insight.sql: the statements the analytics module (DOC-23) and the api (DOC-32) run against the insight
 * tables parse and behave, and the CHECK constraints hold the invariants the detectors rely on. Everything runs as
 * the runtime role that owns the statement, in a transaction that is rolled back.
 */
class InsightSchemaIT {

    private static final String DB = "pti_warehouse";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    /** DOC-23 §5.7, parameters inlined for one episode that stays open. */
    private static final String UPSERT_BUNCHING = """
            INSERT INTO insight.insight_bus_bunching AS b (
              id, route_id, direction_id, vehicle_leader, vehicle_follower, trip_leader, trip_follower,
              episode_start, episode_end, status, close_reason, scheduled_headway_seconds, threshold_seconds,
              min_gap_seconds, last_gap_seconds, open_stop_id, evaluation_count, last_evaluated_at, batch_id)
            VALUES (
              'c69bcc55-ae25-5b9c-931c-e690e8e5220d', '18', 0, '1234', '1250', 't1', 't2',
              TIMESTAMPTZ '2026-09-29 21:19:30Z', %s, '%s', %s, 600, 300,
              %d, %d, '56043', %d, TIMESTAMPTZ '2026-09-29 21:20:00Z', gen_random_uuid())
            ON CONFLICT (id) DO UPDATE SET
              trip_leader = excluded.trip_leader, trip_follower = excluded.trip_follower,
              episode_end = excluded.episode_end, status = excluded.status, close_reason = excluded.close_reason,
              scheduled_headway_seconds = excluded.scheduled_headway_seconds, threshold_seconds = excluded.threshold_seconds,
              min_gap_seconds = excluded.min_gap_seconds, last_gap_seconds = excluded.last_gap_seconds,
              open_stop_id = excluded.open_stop_id, evaluation_count = excluded.evaluation_count,
              last_evaluated_at = excluded.last_evaluated_at, batch_id = excluded.batch_id, updated_at = now()""";

    /** DOC-23 §9.6, for one window, with the summary as the only input that changes between runs. */
    private static final String UPSERT_ANOMALY = """
            INSERT INTO insight.insight_ticketing_anomaly AS a (
              id, sale_point_id, window_start, window_end, trigger, txn_count, refund_count, refund_ratio, amount_sum,
              baseline_mean, baseline_stddev, z_score, summary, detected_at, batch_id)
            VALUES (
              '5d0f3e5c-2b8e-5a0c-9a52-6e1d2d5b7f10', 'KIOSK-900', TIMESTAMPTZ '2026-09-29 22:00:00Z',
              TIMESTAMPTZ '2026-09-29 22:15:00Z', 'VOLUME', 96, 1, 0.0104, 252.50, 12.40, 3.10, 9999.99,
              '%s'::jsonb, TIMESTAMPTZ '2026-09-29 22:15:00Z', gen_random_uuid())
            ON CONFLICT (id) DO UPDATE SET
              trigger = excluded.trigger, txn_count = excluded.txn_count, refund_count = excluded.refund_count,
              refund_ratio = excluded.refund_ratio, amount_sum = excluded.amount_sum,
              baseline_mean = excluded.baseline_mean, baseline_stddev = excluded.baseline_stddev,
              z_score = excluded.z_score, summary = excluded.summary, batch_id = excluded.batch_id,
              category            = CASE WHEN a.summary = excluded.summary THEN a.category            END,
              category_confidence = CASE WHEN a.summary = excluded.summary THEN a.category_confidence END,
              severity            = CASE WHEN a.summary = excluded.summary THEN a.severity            END,
              severity_confidence = CASE WHEN a.summary = excluded.summary THEN a.severity_confidence END,
              model_version       = CASE WHEN a.summary = excluded.summary THEN a.model_version       END,
              enriched_at         = CASE WHEN a.summary = excluded.summary THEN a.enriched_at         END,
              enrichment_status   = CASE WHEN a.summary = excluded.summary THEN a.enrichment_status ELSE 'PENDING' END,
              enrichment_attempts = CASE WHEN a.summary = excluded.summary THEN a.enrichment_attempts ELSE 0 END,
              enrichment_lease_until = CASE WHEN a.summary = excluded.summary THEN a.enrichment_lease_until END
            RETURNING (xmax = 0) AS inserted""";

    /** DOC-23 §10.2. */
    private static final String INSERT_ALERT = """
            INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title, body, dedup_key)
            VALUES ('fb899421-12d7-5369-ac3f-51d7b885dfcf', 'BUNCHING', 1, 'OPERATIONS', '18',
                    'insight.insight_bus_bunching', 'c69bcc55-ae25-5b9c-931c-e690e8e5220d', 'Bus bunching', '{}'::jsonb,
                    'bunching:c69bcc55-ae25-5b9c-931c-e690e8e5220d')
            ON CONFLICT ON CONSTRAINT alert_event_dedup_uk DO NOTHING
            RETURNING id, audience, created_at""";

    /** DOC-23 §7.1 with the parameters inlined. */
    private static final String AGGREGATE_ETA = """
            WITH obs AS (
              SELECT stop_id,
                     extract(isodow FROM scheduled_arrival AT TIME ZONE 'America/Chicago')::smallint AS day_of_week,
                     extract(hour   FROM scheduled_arrival AT TIME ZONE 'America/Chicago')::smallint AS hour_of_day,
                     delay_seconds
              FROM dw.fact_trip_update
              WHERE service_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-29'
                AND route_id = '18'
                AND is_observed AND schedule_relationship = 'SCHEDULED'
                AND delay_seconds IS NOT NULL AND scheduled_arrival IS NOT NULL
                AND coalesce(arrival_time, departure_time) >= TIMESTAMPTZ '2026-09-01 00:00:00Z'
                AND coalesce(arrival_time, departure_time) <  TIMESTAMPTZ '2026-09-29 21:00:00Z'
            ),
            agg AS (
              SELECT stop_id, day_of_week, hour_of_day,
                     round(avg(delay_seconds), 1)                               AS avg_delay_seconds,
                     percentile_disc(0.5) WITHIN GROUP (ORDER BY delay_seconds) AS median_delay_seconds,
                     percentile_disc(0.9) WITHIN GROUP (ORDER BY delay_seconds) AS p90_delay_seconds,
                     count(*)::int                                              AS sample_count
              FROM obs
              GROUP BY stop_id, day_of_week, hour_of_day
            ),
            upserted AS (
              INSERT INTO insight.insight_eta_prediction AS p (
                route_id, stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds,
                p90_delay_seconds, sample_count, window_start, window_end, computed_at, batch_id)
              SELECT '18', stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds,
                     p90_delay_seconds, sample_count, DATE '2026-09-01', DATE '2026-09-28',
                     TIMESTAMPTZ '2026-09-29 21:00:00Z', gen_random_uuid()
              FROM agg
              ON CONFLICT (route_id, stop_id, day_of_week, hour_of_day) DO UPDATE SET
                avg_delay_seconds = excluded.avg_delay_seconds, median_delay_seconds = excluded.median_delay_seconds,
                p90_delay_seconds = excluded.p90_delay_seconds, sample_count = excluded.sample_count,
                window_start = excluded.window_start, window_end = excluded.window_end,
                computed_at = excluded.computed_at, batch_id = excluded.batch_id
              RETURNING 1
            )
            DELETE FROM insight.insight_eta_prediction p
            WHERE p.route_id = '18'
              AND NOT EXISTS (SELECT 1 FROM agg a
                              WHERE (a.stop_id, a.day_of_week, a.hour_of_day) = (p.stop_id, p.day_of_week, p.hour_of_day))""";

    /** DOC-23 §8.1 with the parameters inlined. */
    private static final String COMPUTE_OTP = """
            WITH agg AS (
              SELECT route_id,
                     count(*) FILTER (WHERE delay_seconds BETWEEN -300 AND 300) ::int AS on_time_count,
                     count(*) FILTER (WHERE delay_seconds < -300)                ::int AS early_count,
                     count(*) FILTER (WHERE delay_seconds >  300)                ::int AS late_count,
                     count(*)::int                                                     AS observation_count,
                     count(DISTINCT trip_id)::int                                      AS trip_count
              FROM dw.fact_trip_update
              WHERE service_date = DATE '2026-09-28'
                AND is_observed AND schedule_relationship = 'SCHEDULED' AND delay_seconds IS NOT NULL
              GROUP BY route_id
            ),
            upserted AS (
              INSERT INTO insight.insight_otp_scorecard AS s (
                route_id, service_date, otp_percentage, on_time_count, early_count, late_count, observation_count,
                trip_count, early_tolerance_seconds, late_tolerance_seconds, computed_at, batch_id)
              SELECT route_id, DATE '2026-09-28', round(on_time_count * 100.0 / observation_count, 2), on_time_count,
                     early_count, late_count, observation_count, trip_count, 300, 300,
                     TIMESTAMPTZ '2026-09-29 08:00:00Z', gen_random_uuid()
              FROM agg
              ON CONFLICT (route_id, service_date) DO UPDATE SET
                otp_percentage = excluded.otp_percentage, on_time_count = excluded.on_time_count,
                early_count = excluded.early_count, late_count = excluded.late_count,
                observation_count = excluded.observation_count, trip_count = excluded.trip_count,
                early_tolerance_seconds = excluded.early_tolerance_seconds,
                late_tolerance_seconds = excluded.late_tolerance_seconds,
                computed_at = excluded.computed_at, batch_id = excluded.batch_id
              RETURNING 1
            )
            DELETE FROM insight.insight_otp_scorecard s
            WHERE s.service_date = DATE '2026-09-28'
              AND NOT EXISTS (SELECT 1 FROM agg a WHERE a.route_id = s.route_id)""";

    @Test
    void bunchingUpsertKeepsEnrichmentAndClosesWithAReason() throws SQLException {
        inRolledBackTransaction("etl_writer", c -> {
            execute(c, UPSERT_BUNCHING.formatted("NULL", "OPEN", "NULL", 140, 140, 2));
            execute(
                    c,
                    "UPDATE insight.insight_bus_bunching SET enrichment_status = 'DONE' WHERE id ="
                            + " 'c69bcc55-ae25-5b9c-931c-e690e8e5220d'");

            execute(
                    c,
                    UPSERT_BUNCHING.formatted(
                            "TIMESTAMPTZ '2026-09-29 21:25:00Z'", "CLOSED", "'GAP_RECOVERED'", 90, 480, 12));

            assertThat(queryStrings(c, """
                            SELECT status, close_reason, enrichment_status, min_gap_seconds::text, evaluation_count::text
                            FROM insight.insight_bus_bunching WHERE id = 'c69bcc55-ae25-5b9c-931c-e690e8e5220d'""")).containsExactly("CLOSED GAP_RECOVERED DONE 90 12");
        });
    }

    @Test
    void closedBunchingEpisodeWithoutAReasonIsRejected() throws SQLException {
        inRolledBackTransaction(
                "etl_writer",
                c -> assertSqlState(
                        c,
                        UPSERT_BUNCHING.formatted("TIMESTAMPTZ '2026-09-29 21:25:00Z'", "CLOSED", "NULL", 90, 480, 12),
                        CHECK_VIOLATION));
    }

    @Test
    void disruptionCloseReasonAndEnrichmentInvariantsAreEnforced() throws SQLException {
        String insert = """
                INSERT INTO insight.insight_service_disruption (id, route_id, direction_id, episode_start, episode_end,
                  status, close_reason, baseline_mean_seconds, baseline_stddev_seconds, current_avg_delay_seconds,
                  current_z_score, peak_avg_delay_seconds, peak_z_score, sample_count, last_bucket, enrichment_status,
                  batch_id)
                VALUES (gen_random_uuid(), '19', 0, TIMESTAMPTZ '2026-09-29 20:58:00Z', %s, '%s', %s, 61.4, 38.0, 212.7,
                  3.98, 230.1, 4.44, 57, TIMESTAMPTZ '2026-09-29 21:19:00Z', '%s', gen_random_uuid())""";
        inRolledBackTransaction("etl_writer", c -> {
            execute(c, insert.formatted("TIMESTAMPTZ '2026-09-29 22:00:00Z'", "CLOSED", "'MAX_DURATION'", "PENDING"));
            assertSqlState(c, insert.formatted("NULL", "CLOSED", "'RECOVERED'", "PENDING"), CHECK_VIOLATION);
        });
        inRolledBackTransaction(
                "etl_writer",
                c -> assertSqlState(c, insert.formatted("NULL", "OPEN", "NULL", "DONE"), CHECK_VIOLATION));
    }

    @Test
    void ticketingUpsertResetsEnrichmentOnlyWhenTheSummaryChanges() throws SQLException {
        inRolledBackTransaction("etl_writer", c -> {
            assertThat(queryStrings(c, UPSERT_ANOMALY.formatted("{\"txnCount\": 96}")))
                    .containsExactly("t");
            execute(c, """
                    UPDATE insight.insight_ticketing_anomaly SET category = 'normal', category_confidence = 0.9,
                      enrichment_status = 'DONE' WHERE sale_point_id = 'KIOSK-900'""");

            assertThat(queryStrings(c, UPSERT_ANOMALY.formatted("{\"txnCount\": 96}")))
                    .containsExactly("f");
            assertThat(queryStrings(
                            c,
                            "SELECT category || ' ' || enrichment_status FROM insight.insight_ticketing_anomaly"
                                    + " WHERE sale_point_id = 'KIOSK-900'"))
                    .containsExactly("normal DONE");

            assertThat(queryStrings(c, UPSERT_ANOMALY.formatted("{\"txnCount\": 97}")))
                    .containsExactly("f");
            assertThat(queryStrings(c, """
                            SELECT coalesce(category, 'null') || ' ' || enrichment_status
                            FROM insight.insight_ticketing_anomaly WHERE sale_point_id = 'KIOSK-900'""")).containsExactly("null PENDING");
        });
    }

    @Test
    void alertInsertIsIdempotentOnTheDedupKey() throws SQLException {
        inRolledBackTransaction("etl_writer", c -> {
            assertThat(queryStrings(c, INSERT_ALERT)).hasSize(1);
            assertThat(queryStrings(c, INSERT_ALERT)).isEmpty();
        });
    }

    @Test
    void dispatchSuggestionIsUniquePerBunchingEpisode() throws SQLException {
        String insert = """
                INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
                  state_snapshot, model_version)
                VALUES (gen_random_uuid(), 'c69bcc55-ae25-5b9c-931c-e690e8e5220d', '18', 'hold_follower', 0.82, '{}',
                  'test')""";
        inRolledBackTransaction("triage_writer", c -> {
            execute(c, insert);
            assertSqlState(c, insert, UNIQUE_VIOLATION);
        });
    }

    @Test
    void bunchingCursorStaysOnTheFifteenSecondGrid() throws SQLException {
        String insert =
                "INSERT INTO insight.analytics_bunching_cursor (route_id, last_tick) VALUES ('18', TIMESTAMPTZ '%s')";
        inRolledBackTransaction("etl_writer", c -> {
            execute(c, insert.formatted("2026-09-29 21:20:15Z"));
            assertSqlState(c, insert.formatted("2026-09-29 21:20:16Z"), CHECK_VIOLATION);
        });
    }

    @Test
    void baselineSnapshotsAreOnWholeHours() throws SQLException {
        String insert = """
                INSERT INTO insight.analytics_baseline_snapshot (snapshot_hour, route_id, direction_id, ewma_mean, ewma_var,
                  bucket_count, last_bucket, consecutive_high, consecutive_low)
                VALUES (TIMESTAMPTZ '%s', '18', 0, 61.4, 1444, 61, TIMESTAMPTZ '2026-09-29 21:00:00Z', 0, 0)""";
        inRolledBackTransaction("etl_writer", c -> {
            execute(c, insert.formatted("2026-09-29 21:00:00Z"));
            assertSqlState(c, insert.formatted("2026-09-29 21:30:00Z"), CHECK_VIOLATION);
        });
    }

    @Test
    void etaAndOtpAggregationStatementsRunAgainstTheSchema() throws SQLException {
        inRolledBackTransaction("etl_writer", c -> {
            execute(c, AGGREGATE_ETA);
            execute(c, COMPUTE_OTP);
        });
    }

    /** DOC-32 list queries (E-10, E-12) run as api_reader, which can read the episodes, suggestions and alerts. */
    @Test
    void apiListQueriesRunAsTheReader() throws SQLException {
        inRolledBackTransaction("api_reader", c -> {
            execute(c, """
                    SELECT b.*, s.id AS suggestion_id, s.action, s.action_confidence
                    FROM insight.insight_bus_bunching b
                    LEFT JOIN insight.insight_dispatch_suggestion s ON s.bunching_id = b.id
                    WHERE b.episode_start >= TIMESTAMPTZ '2026-09-29 00:00:00Z' - interval '3 hours'
                      AND b.episode_start < TIMESTAMPTZ '2026-09-30 00:00:00Z'
                      AND coalesce(b.episode_end, 'infinity') >= TIMESTAMPTZ '2026-09-29 00:00:00Z'
                      AND b.route_id = ANY(ARRAY['18'])
                      AND (b.episode_start, b.id) < (TIMESTAMPTZ '2026-09-30 00:00:00Z', gen_random_uuid())
                    ORDER BY b.episode_start DESC, b.id DESC
                    LIMIT 51""");
            execute(c, """
                    SELECT d.*, a.audience, a.severity
                    FROM insight.insight_service_disruption d
                    JOIN ops.alert_event a ON a.dedup_key = 'disruption:' || d.id::text
                    WHERE d.episode_start >= TIMESTAMPTZ '2026-09-29 00:00:00Z' - interval '3 hours'
                      AND coalesce(d.episode_end, 'infinity') >= TIMESTAMPTZ '2026-09-29 00:00:00Z'
                      AND a.audience = 'PUBLIC'
                    ORDER BY d.episode_start DESC, d.id DESC
                    LIMIT 51""");
            execute(c, """
                    SELECT stop_id, avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count,
                           window_start, window_end, computed_at
                    FROM insight.insight_eta_prediction
                    WHERE route_id = '18' AND day_of_week = 2 AND hour_of_day = 17""");
            execute(c, """
                    SELECT route_id, service_date, otp_percentage, on_time_count, early_count, late_count,
                           observation_count, trip_count, early_tolerance_seconds, late_tolerance_seconds
                    FROM insight.insight_otp_scorecard
                    WHERE service_date BETWEEN DATE '2026-09-22' AND DATE '2026-09-28'""");
        });
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    private static void inRolledBackTransaction(String role, Work work) throws SQLException {
        try (Connection c = MigratedDatabases.connect(DB, role)) {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Runs the statement in a savepoint, so the surrounding transaction survives the expected error. */
    private static void assertSqlState(Connection c, String sql, String sqlState) throws SQLException {
        var savepoint = c.setSavepoint();
        assertThatThrownBy(() -> execute(c, sql))
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
        c.rollback(savepoint);
    }

    /** The first column of every result row, for a statement that returns rows (also INSERT ... RETURNING). */
    private static List<String> queryStrings(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            if (s.execute()) {
                try (ResultSet rs = s.getResultSet()) {
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder(rs.getString(1));
                        for (int i = 2; i <= rs.getMetaData().getColumnCount(); i++) {
                            row.append(' ').append(rs.getString(i));
                        }
                        rows.add(row.toString());
                    }
                }
            }
        }
        return rows;
    }
}
