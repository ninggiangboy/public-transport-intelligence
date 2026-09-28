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

/** DOC-17 §7.1: the pti_warehouse permission matrix, case numbers as in the document. */
class WarehouseGrantsIT {

    private static final String DB = "pti_warehouse";
    private static final String API = "api_reader";
    private static final String ETL = "etl_writer";
    private static final String TRIAGE = "triage_writer";
    private static final String OPERATOR = "replay_operator";
    private static final String EXPERIMENT = "experiment_runner";

    /** Seeded once by pti_owner; every case rolls back. */
    private static final String DEAD_LETTER = "'00000000-0000-7000-8000-00000000d1e7'";

    private static final String ALERT = "'00000000-0000-7000-8000-0000000a1e47'";

    private static final String INSERT_FACT = """
            INSERT INTO dw.fact_vehicle_position (service_date, vehicle_id, event_timestamp, trip_id, route_id,
              direction_id, lat, lon, current_stop_sequence, stop_id, current_status, schema_version, payload_hash, batch_id)
            VALUES (DATE '2026-09-29', 'v1', TIMESTAMPTZ '2026-09-29 12:00:00Z', 't1', '18', 0, 44.9, -93.2, 1, 's1',
              'IN_TRANSIT_TO', 1, repeat('a', 64), gen_random_uuid())""";

    private static final String INSERT_DISPATCH_SUGGESTION = """
            INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
              state_snapshot, model_version)
            VALUES (gen_random_uuid(), gen_random_uuid(), '18', 'no_action', 0.5, '{}', 'test')""";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = MigratedDatabases.connect(DB, "pti_owner");
                Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO ops.dead_letter (id, source, stage, error_class, error_message, raw_payload, batch_id)
                    VALUES (%s, 'GTFS_RT_VEHICLE_POSITION', 'SCHEMA', 'SchemaViolation', 'test', '{}', gen_random_uuid())
                    ON CONFLICT DO NOTHING""".formatted(DEAD_LETTER));
            s.execute("""
                    INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)
                    VALUES (%s, 'INFRA', 1, 'ENGINEERING', 'Grants test', 'grants-test')
                    ON CONFLICT DO NOTHING""".formatted(ALERT));
        }
    }

    static Stream<GrantCase> cases() {
        return Stream.of(
                allowed(1, DB, API, "select fact", "SELECT * FROM dw.fact_vehicle_position LIMIT 1"),
                allowed(2, DB, API, "select ops view", "SELECT * FROM ops.ops_job_run_v LIMIT 1"),
                denied(3, DB, API, "select batch.*", "SELECT * FROM batch.batch_job_execution LIMIT 1"),
                denied(4, DB, API, "insert fact", INSERT_FACT),
                denied(
                        5,
                        DB,
                        API,
                        "update dead_letter",
                        "UPDATE ops.dead_letter SET status = 'MANUAL' WHERE id = " + DEAD_LETTER),
                denied(6, DB, API, "select dedup_registry", "SELECT * FROM ops.dedup_registry LIMIT 1"),
                denied(7, DB, API, "select analytics state", "SELECT * FROM insight.analytics_route_baseline LIMIT 1")
                        .requires("insight.analytics_route_baseline"),
                denied(8, DB, ETL, "drop fact", "DROP TABLE dw.fact_vehicle_position"),
                denied(9, DB, ETL, "truncate fact", "TRUNCATE dw.fact_vehicle_position"),
                denied(10, DB, ETL, "create table in dw", "CREATE TABLE dw.grants_probe (id INT)"),
                denied(11, DB, ETL, "create table in public", "CREATE TABLE public.grants_probe (id INT)"),
                denied(
                        12,
                        DB,
                        ETL,
                        "write dim_date",
                        "UPDATE dw.dim_date SET is_weekend = is_weekend WHERE date_key = 20260101"),
                allowed(13, DB, ETL, "insert dead_letter", """
                        INSERT INTO ops.dead_letter (id, source, stage, error_class, error_message, raw_payload, batch_id)
                        VALUES (gen_random_uuid(), 'GTFS_RT_TRIP_UPDATE', 'BUSINESS', 'X', 'm', '{}', gen_random_uuid())"""),
                denied(
                        14,
                        DB,
                        ETL,
                        "etl edits triage cols",
                        "UPDATE ops.dead_letter SET category = 'unknown', category_confidence = 0.5 WHERE id = "
                                + DEAD_LETTER),
                allowed(
                        15,
                        DB,
                        ETL,
                        "dlq_action_log identity insert",
                        "INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor) VALUES (" + DEAD_LETTER
                                + ", 'REPLAYED', 'system:etl-batch')"),
                allowed(16, DB, ETL, "dq_check_result identity insert", """
                        INSERT INTO ops.dq_check_result (rule_id, scope, table_name, violation_count)
                        VALUES ('DQ-01', 'TABLE', 'dw.fact_vehicle_position', 0)"""),
                denied(
                        17,
                        DB,
                        ETL,
                        "write runtime_flag",
                        "UPDATE ops.runtime_flag SET value = 'false' WHERE key = 'triage.dlq.enabled'"),
                denied(18, DB, ETL, "insert dispatch suggestion", INSERT_DISPATCH_SUGGESTION)
                        .requires("insight.insight_dispatch_suggestion"),
                allowed(19, DB, ETL, "batch sequence", "SELECT nextval('batch.batch_job_execution_seq')"),
                allowed(
                        20,
                        DB,
                        ETL,
                        "ensure_partitions",
                        "SELECT dw.ensure_partitions('fact_vehicle_position', DATE '2026-01-01', DATE '2026-01-01')"),
                allowed(21, DB, TRIAGE, "triage update", """
                        UPDATE ops.dead_letter SET status = 'TRIAGED', category = 'unknown', category_confidence = 0.5,
                          severity = 1, severity_confidence = 0.5, model_version = 'test', triaged_at = now(),
                          triage_attempts = 1, updated_at = now()
                        WHERE id = %s""".formatted(DEAD_LETTER)),
                denied(
                        22,
                        DB,
                        TRIAGE,
                        "triage edits payload",
                        "UPDATE ops.dead_letter SET edited_payload = '{}' WHERE id = " + DEAD_LETTER),
                denied(
                        23,
                        DB,
                        TRIAGE,
                        "triage resolves",
                        "UPDATE ops.dead_letter SET status = 'RESOLVED', resolved_by = 'auto', resolved_at = now()"
                                + " WHERE id = " + DEAD_LETTER),
                denied(24, DB, TRIAGE, "insert fact", INSERT_FACT),
                denied(
                        25,
                        DB,
                        TRIAGE,
                        "ensure_partitions",
                        "SELECT dw.ensure_partitions('fact_vehicle_position', DATE '2026-01-01', DATE '2026-01-01')"),
                denied(
                        26,
                        DB,
                        TRIAGE,
                        "write runtime_flag",
                        "UPDATE ops.runtime_flag SET value = 'false' WHERE key = 'triage.dlq.enabled'"),
                allowed(27, DB, OPERATOR, "resolve dead letter", """
                        UPDATE ops.dead_letter SET status = 'RESOLVED', resolved_by = 'user:alice', resolved_at = now(),
                          updated_at = now()
                        WHERE id = %s""".formatted(DEAD_LETTER)),
                denied(
                        28,
                        DB,
                        OPERATOR,
                        "edit triage cols",
                        "UPDATE ops.dead_letter SET category = 'unknown', category_confidence = 0.5 WHERE id = "
                                + DEAD_LETTER),
                denied(29, DB, OPERATOR, "delete dead letter", "DELETE FROM ops.dead_letter WHERE id = " + DEAD_LETTER),
                allowed(30, DB, OPERATOR, "toggle flag", """
                        UPDATE ops.runtime_flag SET value = 'false', updated_by = 'user:alice', updated_at = now()
                        WHERE key = 'triage.dlq.enabled'"""),
                denied(
                        31,
                        DB,
                        OPERATOR,
                        "rename flag",
                        "UPDATE ops.runtime_flag SET key = 'triage.dlq.renamed' WHERE key = 'triage.dlq.enabled'"),
                allowed(
                        32,
                        DB,
                        OPERATOR,
                        "action log identity insert",
                        "INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor) VALUES (" + DEAD_LETTER
                                + ", 'EDITED', 'user:alice')"),
                denied(33, DB, OPERATOR, "read facts", "SELECT * FROM dw.fact_vehicle_position LIMIT 1"),
                denied(
                        34,
                        DB,
                        OPERATOR,
                        "job status update",
                        "UPDATE ops.job_request SET status = 'DONE', finished_at = now() WHERE id = gen_random_uuid()"),
                allowed(35, DB, OPERATOR, "feedback", """
                                UPDATE insight.insight_dispatch_suggestion
                                SET operator_feedback = 'accepted', feedback_by = 'user:alice', feedback_at = now()
                                WHERE id = gen_random_uuid()""").requires("insight.insight_dispatch_suggestion"),
                denied(
                                36,
                                DB,
                                OPERATOR,
                                "rewrite suggestion",
                                "UPDATE insight.insight_dispatch_suggestion SET action = 'no_action'"
                                        + " WHERE id = gen_random_uuid()")
                        .requires("insight.insight_dispatch_suggestion"),
                allowed(37, DB, EXPERIMENT, "read facts", "SELECT * FROM dw.fact_vehicle_position LIMIT 1"),
                allowed(38, DB, EXPERIMENT, "truncate exp", "TRUNCATE exp.exp_fact_vehicle_position"),
                denied(39, DB, EXPERIMENT, "truncate dw", "TRUNCATE dw.fact_vehicle_position"),
                denied(40, DB, EXPERIMENT, "read dedup", "SELECT * FROM ops.dedup_registry LIMIT 1"),
                denied(41, DB, EXPERIMENT, "read batch", "SELECT * FROM batch.batch_job_execution LIMIT 1"),
                allowed(42, DB, API, "select ops_job_step_v", "SELECT * FROM ops.ops_job_step_v LIMIT 1"),
                allowed(
                        43,
                        DB,
                        API,
                        "select ops_job_execution_param_v",
                        "SELECT * FROM ops.ops_job_execution_param_v LIMIT 1"),
                allowed(44, DB, EXPERIMENT, "select ops_job_step_v", "SELECT * FROM ops.ops_job_step_v LIMIT 1"),
                denied(45, DB, TRIAGE, "insert alert_event", """
                        INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)
                        VALUES (gen_random_uuid(), 'DLQ_SEVERE', 2, 'ENGINEERING', 't', gen_random_uuid()::text)"""),
                allowed(46, DB, TRIAGE, "update alert_event severity, audience, body", """
                        UPDATE ops.alert_event SET severity = 2, audience = 'OPERATIONS', body = '{"k": 1}'
                        WHERE id = %s""".formatted(ALERT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void permissionMatrix(GrantCase grantCase) throws SQLException {
        grantCase.verify();
    }
}
