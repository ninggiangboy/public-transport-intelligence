package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.db.MigratedDatabases;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code OpsRetentionJob} with its {@code purgeInsight} step (DOC-23 §12.3, AN-I-10): it deletes what has expired from
 * {@code insight} and keeps the rest, an open episode however old. The {@code purgeOps} step before it is unchanged
 * and has its own tests ({@code BatchJobsIT} L-02, L-03). Rows are seeded as the owner: {@code etl_writer} may delete
 * dispatch suggestions but never insert them (DOC-17 §4.1).
 */
class InsightRetentionJobIT extends BatchContextSupport {

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    MeterRegistry meters;

    private final String route = "AN4J-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        for (String table : List.of(
                "insight.insight_bus_bunching",
                "insight.insight_service_disruption",
                "insight.insight_otp_scorecard",
                "insight.insight_dispatch_suggestion",
                "insight.analytics_baseline_snapshot",
                "insight.analytics_bunching_cursor")) {
            owner("DELETE FROM " + table + " WHERE route_id LIKE '" + route + "%'");
        }
        owner("DELETE FROM insight.insight_ticketing_anomaly WHERE sale_point_id = '" + route + "'");
    }

    private static void owner(String sql) {
        try (Connection c = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private long count(String table, String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
    }

    @Test
    void anI10TheJobDeletesWhatExpiredFromInsightAndKeepsTheOpenAndTheRecent() {
        // A year is 365 days of the business clock; these are far on either side of it.
        bunching("old-closed", "now() - interval '400 days'", "CLOSED", "now() - interval '399 days'");
        bunching("old-open", "now() - interval '400 days'", "OPEN", "NULL");
        bunching("recent-closed", "now() - interval '10 days'", "CLOSED", "now() - interval '9 days'");
        owner("""
                INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
                  state_snapshot, model_version, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), '%s', 'no_action', 0.5, '{}', 't',
                        now() - interval '400 days'),
                       (gen_random_uuid(), gen_random_uuid(), '%s', 'no_action', 0.5, '{}', 't', now())""".formatted(route, route));
        owner("""
                INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage, on_time_count,
                  early_count, late_count, observation_count, trip_count, early_tolerance_seconds,
                  late_tolerance_seconds, computed_at, batch_id)
                VALUES ('%s', CURRENT_DATE - 400, 100, 1, 0, 0, 1, 1, 300, 300, now(), gen_random_uuid()),
                       ('%s', CURRENT_DATE - 2, 100, 1, 0, 0, 1, 1, 300, 300, now(), gen_random_uuid())""".formatted(route, route));
        owner("""
                INSERT INTO insight.analytics_baseline_snapshot (snapshot_hour, route_id, direction_id, ewma_mean,
                  ewma_var, bucket_count, last_bucket, consecutive_high, consecutive_low)
                VALUES (date_trunc('hour', now() - interval '40 days'), '%s', 0, 60, 900, 61, now(), 0, 0),
                       (date_trunc('hour', now() - interval '2 days'), '%s', 0, 60, 900, 61, now(), 0, 0)""".formatted(route, route));
        owner("""
                INSERT INTO insight.insight_ticketing_anomaly (id, sale_point_id, window_start, window_end, trigger,
                  txn_count, refund_count, refund_ratio, amount_sum, summary, detected_at, batch_id)
                VALUES (gen_random_uuid(), '%s', now() - interval '401 days', now() - interval '401 days' + interval '15 minutes',
                        'VOLUME', 30, 0, 0, 10, '{}', now() - interval '401 days', gen_random_uuid())""".formatted(route));

        JobExecution execution = awaitEnd(launcher.launchIfNew(
                        PtiJob.OPS_RETENTION,
                        JobParams.identity(PtiJob.OPS_RETENTION, "insight-" + UUID.randomUUID())
                                .toJobParameters())
                .orElseThrow());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .containsExactly("purgeOps", "purgeInsight");
        assertThat(count(
                        "insight.insight_bus_bunching", "route_id = '" + route + "' AND vehicle_leader = 'old-closed'"))
                .isZero();
        assertThat(count("insight.insight_bus_bunching", "route_id = '" + route + "' AND vehicle_leader = 'old-open'"))
                .as("an open episode is never deleted")
                .isEqualTo(1);
        assertThat(count(
                        "insight.insight_bus_bunching",
                        "route_id = '" + route + "' AND vehicle_leader = 'recent-closed'"))
                .isEqualTo(1);
        assertThat(count("insight.insight_dispatch_suggestion", "route_id = '" + route + "'"))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT service_date FROM insight.insight_otp_scorecard WHERE route_id = ?",
                        LocalDate.class,
                        route))
                .containsExactly(LocalDate.now().minusDays(2));
        assertThat(count("insight.analytics_baseline_snapshot", "route_id = '" + route + "'"))
                .isEqualTo(1);
        assertThat(count("insight.insight_ticketing_anomaly", "sale_point_id = '" + route + "'"))
                .isZero();

        String summary = execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals("purgeInsight"))
                .findFirst()
                .orElseThrow()
                .getExitStatus()
                .getExitDescription();
        assertThat(summary)
                .contains("insight.insight_bus_bunching: ")
                .contains("insight.insight_dispatch_suggestion: ");
    }

    @Test
    void theStepCountsWhatItDeletedInPtiRetentionDeleted() {
        bunching("counted", "now() - interval '500 days'", "CLOSED", "now() - interval '499 days'");

        awaitEnd(launcher.launchIfNew(
                        PtiJob.OPS_RETENTION,
                        JobParams.identity(PtiJob.OPS_RETENTION, "insight-" + UUID.randomUUID())
                                .toJobParameters())
                .orElseThrow());

        assertThat(count("insight.insight_bus_bunching", "route_id = '" + route + "'"))
                .isZero();
        assertThat(meters.get("pti.retention.deleted")
                        .tag("table", "insight.insight_bus_bunching")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }
}
