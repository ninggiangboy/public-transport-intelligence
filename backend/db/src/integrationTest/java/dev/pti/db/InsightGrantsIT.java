package dev.pti.db;

import static dev.pti.db.GrantCase.allowed;
import static dev.pti.db.GrantCase.denied;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * DOC-17 §4.1 for the tables of V7__insight.sql: who reads, writes or enriches which insight and analytics table.
 * Cases 1 to 46 are in {@link WarehouseGrantsIT}, numbered as in DOC-17 §7.1; the cases here continue from 47.
 */
class InsightGrantsIT {

    private static final String DB = "pti_warehouse";
    private static final String API = "api_reader";
    private static final String ETL = "etl_writer";
    private static final String TRIAGE = "triage_writer";
    private static final String OPERATOR = "replay_operator";
    private static final String EXPERIMENT = "experiment_runner";

    /** Seeded once by pti_owner; every case rolls back. */
    private static final String ALERT = "'00000000-0000-7000-8000-0000000a1e48'";

    private static final String BUNCHING = "'00000000-0000-5000-8000-00000000b001'";
    private static final String DISRUPTION = "'00000000-0000-5000-8000-00000000d002'";
    private static final String ANOMALY = "'00000000-0000-5000-8000-00000000a003'";
    private static final String SUGGESTION = "'00000000-0000-5000-8000-00000000e004'";

    private static final String INSERT_BUNCHING = """
            INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
              trip_leader, trip_follower, episode_start, status, scheduled_headway_seconds, threshold_seconds,
              min_gap_seconds, last_gap_seconds, last_evaluated_at, batch_id)
            VALUES (gen_random_uuid(), '18', 0, '1500', '1501', 't1', 't2', TIMESTAMPTZ '2026-09-29 21:00:00Z',
              'OPEN', 600, 300, 120, 120, TIMESTAMPTZ '2026-09-29 21:00:30Z', gen_random_uuid())""";

    private static final String INSERT_SUGGESTION = """
            INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
              state_snapshot, model_version)
            VALUES (gen_random_uuid(), gen_random_uuid(), '18', 'hold_follower', 0.82, '{}', 'test')""";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = MigratedDatabases.connect(DB, "pti_owner");
                Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)
                    VALUES (%s, 'BUNCHING', 1, 'OPERATIONS', 'Insight grants test', 'insight-grants-test')
                    ON CONFLICT DO NOTHING""".formatted(ALERT));
            s.execute("""
                    INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader,
                      vehicle_follower, trip_leader, trip_follower, episode_start, status, scheduled_headway_seconds,
                      threshold_seconds, min_gap_seconds, last_gap_seconds, last_evaluated_at, batch_id)
                    VALUES (%s, '18', 0, '1432', '1437', 't1', 't2', TIMESTAMPTZ '2026-09-29 21:00:00Z', 'OPEN', 600,
                      300, 120, 120, TIMESTAMPTZ '2026-09-29 21:00:30Z', gen_random_uuid())
                    ON CONFLICT DO NOTHING""".formatted(BUNCHING));
            s.execute("""
                    INSERT INTO insight.insight_service_disruption (id, route_id, direction_id, episode_start, status,
                      baseline_mean_seconds, baseline_stddev_seconds, current_avg_delay_seconds, current_z_score,
                      peak_avg_delay_seconds, peak_z_score, sample_count, last_bucket, batch_id)
                    VALUES (%s, '18', 0, TIMESTAMPTZ '2026-09-29 20:58:00Z', 'OPEN', 61.4, 38.0, 212.7, 3.98, 230.1,
                      4.44, 57, TIMESTAMPTZ '2026-09-29 21:19:00Z', gen_random_uuid())
                    ON CONFLICT DO NOTHING""".formatted(DISRUPTION));
            s.execute("""
                    INSERT INTO insight.insight_ticketing_anomaly (id, sale_point_id, window_start, window_end,
                      trigger, txn_count, refund_count, refund_ratio, amount_sum, summary, detected_at, batch_id)
                    VALUES (%s, 'KIOSK-001', TIMESTAMPTZ '2026-09-29 22:00:00Z', TIMESTAMPTZ '2026-09-29 22:15:00Z',
                      'VOLUME', 96, 1, 0.0104, 252.50, '{}', TIMESTAMPTZ '2026-09-29 22:15:00Z', gen_random_uuid())
                    ON CONFLICT DO NOTHING""".formatted(ANOMALY));
            s.execute("""
                    INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action,
                      action_confidence, state_snapshot, model_version)
                    VALUES (%s, %s, '18', 'hold_follower', 0.82, '{}', 'test')
                    ON CONFLICT DO NOTHING""".formatted(SUGGESTION, BUNCHING));
        }
    }

    /** Cases 47 to 62: api_reader reads the outputs but never the analytics state; experiment_runner only reads. */
    static Stream<GrantCase> readCases() {
        return Stream.of(
                allowed(47, DB, API, "select bunching", "SELECT * FROM insight.insight_bus_bunching LIMIT 1"),
                allowed(48, DB, API, "select dispatch suggestion", "SELECT * FROM insight.insight_dispatch_suggestion"),
                allowed(49, DB, API, "select disruption", "SELECT * FROM insight.insight_service_disruption LIMIT 1"),
                allowed(50, DB, API, "select eta prediction", "SELECT * FROM insight.insight_eta_prediction LIMIT 1"),
                allowed(51, DB, API, "select otp scorecard", "SELECT * FROM insight.insight_otp_scorecard LIMIT 1"),
                allowed(52, DB, API, "select ticketing anomaly", "SELECT * FROM insight.insight_ticketing_anomaly"),
                denied(53, DB, API, "select pair state", "SELECT * FROM insight.analytics_bunching_pair_state LIMIT 1"),
                denied(
                        54,
                        DB,
                        API,
                        "select bunching cursor",
                        "SELECT * FROM insight.analytics_bunching_cursor LIMIT 1"),
                denied(
                        55,
                        DB,
                        API,
                        "select baseline snapshot",
                        "SELECT * FROM insight.analytics_baseline_snapshot LIMIT 1"),
                denied(56, DB, API, "insert bunching", INSERT_BUNCHING),
                denied(
                        57,
                        DB,
                        API,
                        "record feedback",
                        "UPDATE insight.insight_dispatch_suggestion SET operator_feedback = 'accepted',"
                                + " feedback_by = 'user:alice', feedback_at = now() WHERE id = " + SUGGESTION),
                allowed(58, DB, EXPERIMENT, "select bunching", "SELECT * FROM insight.insight_bus_bunching LIMIT 1"),
                allowed(59, DB, EXPERIMENT, "select route baseline", "SELECT * FROM insight.analytics_route_baseline"),
                allowed(
                        60,
                        DB,
                        EXPERIMENT,
                        "select dispatch suggestion",
                        "SELECT * FROM insight.insight_dispatch_suggestion"),
                denied(
                        61,
                        DB,
                        EXPERIMENT,
                        "update bunching",
                        "UPDATE insight.insight_bus_bunching SET last_gap_seconds = 1 WHERE id = " + BUNCHING),
                denied(62, DB, EXPERIMENT, "insert bunching", INSERT_BUNCHING));
    }

    /** Cases 63 to 79: etl_writer owns the analytics output and state, and a few alert columns (DOC-23 §10). */
    static Stream<GrantCase> etlCases() {
        return Stream.of(
                allowed(63, DB, ETL, "insert bunching", INSERT_BUNCHING),
                allowed(
                        64,
                        DB,
                        ETL,
                        "update bunching",
                        "UPDATE insight.insight_bus_bunching SET last_gap_seconds = 1, enrichment_status = 'PENDING',"
                                + " updated_at = now() WHERE id = " + BUNCHING),
                allowed(
                        65,
                        DB,
                        ETL,
                        "delete disruption",
                        "DELETE FROM insight.insight_service_disruption WHERE id = " + DISRUPTION),
                allowed(
                        66,
                        DB,
                        ETL,
                        "reset ticketing anomaly enrichment",
                        "UPDATE insight.insight_ticketing_anomaly SET category = NULL, category_confidence = NULL,"
                                + " enrichment_status = 'PENDING' WHERE id = " + ANOMALY),
                allowed(67, DB, ETL, "write eta prediction", """
                        INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                          avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start,
                          window_end, computed_at, batch_id)
                        VALUES ('18', '56043', 2, 17, 95.4, 90, 180, 12, DATE '2026-09-01', DATE '2026-09-28',
                          TIMESTAMPTZ '2026-09-29 21:00:00Z', gen_random_uuid())"""),
                allowed(68, DB, ETL, "write otp scorecard", """
                        INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage,
                          on_time_count, early_count, late_count, observation_count, trip_count,
                          early_tolerance_seconds, late_tolerance_seconds, computed_at, batch_id)
                        VALUES ('18', DATE '2026-09-28', 60.00, 3, 1, 1, 5, 2, 300, 300,
                          TIMESTAMPTZ '2026-09-29 08:00:00Z', gen_random_uuid())"""),
                allowed(69, DB, ETL, "write route baseline", """
                        INSERT INTO insight.analytics_route_baseline (route_id, direction_id, ewma_mean, ewma_var,
                          bucket_count, last_bucket)
                        VALUES ('18', 0, 61.4, 1444, 61, TIMESTAMPTZ '2026-09-29 21:19:00Z')"""),
                allowed(70, DB, ETL, "write baseline snapshot", """
                        INSERT INTO insight.analytics_baseline_snapshot (snapshot_hour, route_id, direction_id,
                          ewma_mean, ewma_var, bucket_count, last_bucket, consecutive_high, consecutive_low)
                        VALUES (TIMESTAMPTZ '2026-09-29 21:00:00Z', '18', 0, 61.4, 1444, 61,
                          TIMESTAMPTZ '2026-09-29 21:00:00Z', 0, 0)"""),
                allowed(71, DB, ETL, "write pair state", """
                        INSERT INTO insight.analytics_bunching_pair_state (route_id, direction_id, vehicle_leader,
                          vehicle_follower, trip_leader, trip_follower, last_evaluated_at)
                        VALUES ('18', 0, '1432', '1437', 't1', 't2', TIMESTAMPTZ '2026-09-29 21:00:30Z')"""),
                allowed(72, DB, ETL, "write bunching cursor", """
                        INSERT INTO insight.analytics_bunching_cursor (route_id, last_tick)
                        VALUES ('18', TIMESTAMPTZ '2026-09-29 21:00:30Z')"""),
                allowed(73, DB, ETL, "select dispatch suggestion", "SELECT * FROM insight.insight_dispatch_suggestion"),
                denied(
                        74,
                        DB,
                        ETL,
                        "update dispatch suggestion",
                        "UPDATE insight.insight_dispatch_suggestion SET action = 'no_action' WHERE id = " + SUGGESTION),
                denied(
                        75,
                        DB,
                        ETL,
                        "delete dispatch suggestion",
                        "DELETE FROM insight.insight_dispatch_suggestion WHERE id = " + SUGGESTION),
                denied(76, DB, ETL, "truncate bunching", "TRUNCATE insight.insight_bus_bunching"),
                allowed(77, DB, ETL, "insert alert_event", """
                        INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title,
                          body, dedup_key)
                        VALUES (gen_random_uuid(), 'BUNCHING', 1, 'OPERATIONS', '18', 'insight.insight_bus_bunching',
                          'x', 't', '{}', 'bunching:x')"""),
                allowed(
                        78,
                        DB,
                        ETL,
                        "close alert_event",
                        "UPDATE ops.alert_event SET resolved_at = now(), body = body || '{\"withdrawn\": true}',"
                                + " severity = 2, title = 't2' WHERE id = " + ALERT),
                denied(
                        79,
                        DB,
                        ETL,
                        "retarget alert_event audience",
                        "UPDATE ops.alert_event SET audience = 'PUBLIC' WHERE id = " + ALERT));
    }

    /** Cases 80 to 100: triage_writer fills enrichment columns only, replay_operator only records feedback. */
    static Stream<GrantCase> triageAndOperatorCases() {
        return Stream.of(
                allowed(80, DB, TRIAGE, "select analytics state", "SELECT * FROM insight.analytics_route_baseline"),
                allowed(
                        81,
                        DB,
                        TRIAGE,
                        "claim bunching",
                        "UPDATE insight.insight_bus_bunching SET enrichment_status = 'IN_PROGRESS',"
                                + " enrichment_lease_until = now() WHERE id = " + BUNCHING),
                denied(
                        82,
                        DB,
                        TRIAGE,
                        "edit bunching episode",
                        "UPDATE insight.insight_bus_bunching SET last_gap_seconds = 1 WHERE id = " + BUNCHING),
                denied(
                        83,
                        DB,
                        TRIAGE,
                        "close bunching episode",
                        "UPDATE insight.insight_bus_bunching SET status = 'CLOSED', episode_end = now(),"
                                + " close_reason = 'SIGNAL_LOST' WHERE id = " + BUNCHING),
                allowed(
                        84,
                        DB,
                        TRIAGE,
                        "enrich disruption",
                        "UPDATE insight.insight_service_disruption SET data_issue_probability = 0.12,"
                                + " likely_cause = 'traffic', cause_confidence = 0.7, model_version = 'test',"
                                + " enriched_at = now(), enrichment_status = 'DONE', enrichment_attempts = 1,"
                                + " enrichment_lease_until = NULL WHERE id = " + DISRUPTION),
                denied(
                        85,
                        DB,
                        TRIAGE,
                        "edit disruption z-score",
                        "UPDATE insight.insight_service_disruption SET current_z_score = 0 WHERE id = " + DISRUPTION),
                allowed(
                        86,
                        DB,
                        TRIAGE,
                        "classify ticketing anomaly",
                        "UPDATE insight.insight_ticketing_anomaly SET category = 'normal', category_confidence = 0.9,"
                                + " severity = 0, severity_confidence = 0.9, model_version = 'test',"
                                + " enriched_at = now(), enrichment_status = 'DONE' WHERE id = " + ANOMALY),
                denied(
                        87,
                        DB,
                        TRIAGE,
                        "edit ticketing summary",
                        "UPDATE insight.insight_ticketing_anomaly SET summary = '{\"k\": 1}' WHERE id = " + ANOMALY),
                denied(88, DB, TRIAGE, "insert bunching", INSERT_BUNCHING),
                denied(
                        89,
                        DB,
                        TRIAGE,
                        "delete disruption",
                        "DELETE FROM insight.insight_service_disruption WHERE id = " + DISRUPTION),
                denied(
                        90,
                        DB,
                        TRIAGE,
                        "write route baseline",
                        "UPDATE insight.analytics_route_baseline SET ewma_mean = 0"),
                allowed(91, DB, TRIAGE, "insert dispatch suggestion", INSERT_SUGGESTION),
                allowed(
                        92,
                        DB,
                        TRIAGE,
                        "update suggestion model columns",
                        "UPDATE insight.insight_dispatch_suggestion SET action = 'skip_stops',"
                                + " action_confidence = 0.7, state_snapshot = '{}', model_version = 'test2'"
                                + " WHERE id = " + SUGGESTION),
                denied(
                        93,
                        DB,
                        TRIAGE,
                        "record feedback",
                        "UPDATE insight.insight_dispatch_suggestion SET operator_feedback = 'accepted',"
                                + " feedback_by = 'user:alice', feedback_at = now() WHERE id = " + SUGGESTION),
                denied(
                        94,
                        DB,
                        TRIAGE,
                        "delete suggestion",
                        "DELETE FROM insight.insight_dispatch_suggestion WHERE id = " + SUGGESTION),
                allowed(
                        95,
                        DB,
                        OPERATOR,
                        "select dispatch suggestion",
                        "SELECT * FROM insight.insight_dispatch_suggestion"),
                allowed(
                        96,
                        DB,
                        OPERATOR,
                        "record feedback on a real row",
                        "UPDATE insight.insight_dispatch_suggestion SET operator_feedback = 'ignored',"
                                + " feedback_by = 'user:alice', feedback_at = now() WHERE id = " + SUGGESTION),
                denied(97, DB, OPERATOR, "read bunching", "SELECT * FROM insight.insight_bus_bunching LIMIT 1"),
                denied(98, DB, OPERATOR, "read route baseline", "SELECT * FROM insight.analytics_route_baseline"),
                denied(99, DB, OPERATOR, "insert suggestion", INSERT_SUGGESTION),
                denied(
                        100,
                        DB,
                        OPERATOR,
                        "delete suggestion",
                        "DELETE FROM insight.insight_dispatch_suggestion WHERE id = " + SUGGESTION));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"readCases", "etlCases", "triageAndOperatorCases"})
    void permissionMatrix(GrantCase grantCase) throws SQLException {
        grantCase.verify();
    }
}
