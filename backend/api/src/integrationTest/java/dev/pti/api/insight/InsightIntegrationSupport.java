package dev.pti.api.insight;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.db.MigratedDatabases;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rows of the insight and alert tables for the integration tests, put there as the owner of the schema and removed
 * before and after each test (the database is shared by the test classes of the JVM). The ids are fixed so that the
 * tests can name them: one bunching episode with a dispatch suggestion, a public and a hidden disruption episode with
 * their alerts, OTP of two routes, two ticketing anomalies of one sale point.
 */
public abstract class InsightIntegrationSupport extends ApiIntegrationSupport {

    protected static final String BATCH = "0b9d3c52-6a1f-4c1e-9f0a-2a7d1e4c8b10";
    protected static final String BUNCHING = "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c";
    protected static final String SUGGESTION = "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d";
    protected static final String PUBLIC_DISRUPTION = "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a";
    protected static final String HIDDEN_DISRUPTION = "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6b";
    protected static final String ANOMALY_1 = "c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5f";
    protected static final String ANOMALY_2 = "c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e60";

    @Autowired
    protected ApiCaches caches;

    @BeforeEach
    @AfterEach
    void clean() {
        asOwner(
                "DELETE FROM insight.insight_dispatch_suggestion",
                "DELETE FROM insight.insight_bus_bunching",
                "DELETE FROM insight.insight_service_disruption",
                "DELETE FROM insight.insight_otp_scorecard",
                "DELETE FROM insight.insight_ticketing_anomaly",
                "DELETE FROM ops.alert_event",
                "DELETE FROM dw.dim_sale_point",
                "DELETE FROM dw.dim_route",
                "DELETE FROM dw.dim_agency",
                "DELETE FROM dw.gtfs_feed_version");
        caches.names().forEach(name -> caches.cache(name).invalidateAll());
    }

    /** The ACTIVE feed with two routes: {@code 18}, a bus (type 3), and {@code 901}, a light rail line (type 0). */
    protected long installFeedWithRoutes() {
        asOwner(activeFeedSql("a", "2026-08-23"));
        long feed = ownerLong("SELECT feed_version_id FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'");
        asOwner("""
                INSERT INTO dw.dim_agency (feed_version_id, agency_id, agency_name, agency_timezone)
                VALUES (%d, '0', 'Metro Transit', 'America/Chicago')""".formatted(feed), """
                INSERT INTO dw.dim_route (feed_version_id, route_id, agency_id, display_name, route_type)
                VALUES (%d, '18', '0', '18', 3), (%d, '901', '0', 'Blue', 0)""".formatted(feed, feed));
        // The lookup of the feed is cached for a second, "no feed" included: forget what it saw before.
        caches.names().forEach(name -> caches.cache(name).invalidateAll());
        return feed;
    }

    protected void insertBunching(String id, String route, String start, String end, String status) {
        asOwner("""
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, episode_end, status, close_reason,
                  scheduled_headway_seconds, threshold_seconds, min_gap_seconds, last_gap_seconds, open_stop_id,
                  evaluation_count, last_evaluated_at, enrichment_status, batch_id)
                VALUES ('%s', '%s', 0, '1187', 'f-%s', 't-2041', 't-2043', TIMESTAMPTZ '%s', %s, '%s', %s,
                  600, 300, 96, 112, '51418', 29, TIMESTAMPTZ '%s', 'DONE', '%s')""".formatted(
                        id,
                        route,
                        id.substring(id.length() - 4),
                        start,
                        end == null ? "NULL" : "TIMESTAMPTZ '" + end + "'",
                        status,
                        end == null ? "NULL" : "'GAP_RECOVERED'",
                        end == null ? start : end,
                        BATCH));
    }

    protected void insertSuggestion(String id, String bunchingId, String route, String createdAt, String confidence) {
        asOwner("""
                INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action, action_confidence,
                  state_snapshot, model_version, created_at)
                VALUES ('%s', '%s', '%s', 'hold_follower', %s, '{"task": "Suggest one dispatch action."}',
                  'jev@0.2.0', TIMESTAMPTZ '%s')""".formatted(id, bunchingId, route, confidence, createdAt));
    }

    /** A disruption episode and the alert that every episode has, with the given audience. */
    protected void insertDisruption(String id, String route, String start, String audience) {
        asOwner("""
                INSERT INTO insight.insight_service_disruption (id, route_id, direction_id, episode_start, status,
                  baseline_mean_seconds, baseline_stddev_seconds, current_avg_delay_seconds, current_z_score,
                  peak_avg_delay_seconds, peak_z_score, sample_count, affected_stop_ids, last_bucket,
                  data_issue_probability, likely_cause, cause_confidence, model_version, enriched_at,
                  enrichment_status, batch_id)
                VALUES ('%s', '%s', 0, TIMESTAMPTZ '%s', 'OPEN', 61.4, 38.0, 212.7, 3.98, 230.1, 4.44, 57,
                  ARRAY['51418', '51420'], TIMESTAMPTZ '%s', 0.120, 'traffic', 0.710, 'jev@0.2.0',
                  TIMESTAMPTZ '%s', 'DONE', '%s')""".formatted(id, route, start, start, start, BATCH), """
                INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title, body,
                  dedup_key)
                VALUES (gen_random_uuid(), 'DISRUPTION', 1, '%s', '%s', 'insight.insight_service_disruption', '%s',
                  'Delays on route %s', '{}', 'disruption:%s')""".formatted(audience, route, id, route, id));
    }

    protected void insertAlert(String id, String type, String audience, String route, String createdAt, String dedup) {
        asOwner("""
                INSERT INTO ops.alert_event (id, type, severity, audience, route_id, title, body, dedup_key, created_at)
                VALUES ('%s', '%s', 1, '%s', %s, 'An alert', '{"k": "v"}', '%s', TIMESTAMPTZ '%s')""".formatted(id, type, audience, route == null ? "NULL" : "'" + route + "'", dedup, createdAt));
    }

    protected void insertOtp(String route, String date, int onTime, int early, int late, int earlyTolerance) {
        asOwner("""
                INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage, on_time_count,
                  early_count, late_count, observation_count, trip_count, early_tolerance_seconds,
                  late_tolerance_seconds, computed_at, batch_id)
                VALUES ('%s', DATE '%s', %s, %d, %d, %d, %d, 20, %d, 300, TIMESTAMPTZ '2026-09-29 08:00:41Z', '%s')""".formatted(
                        route,
                        date,
                        "%.2f".formatted(onTime * 100.0 / (onTime + early + late)),
                        onTime,
                        early,
                        late,
                        onTime + early + late,
                        earlyTolerance,
                        BATCH));
    }

    protected void insertSalePoint() {
        asOwner("""
                INSERT INTO dw.dim_sale_point (sale_point_id, name, kind, route_id, source, source_lsn, batch_id)
                VALUES ('SP-0142', 'Nicollet Mall Station kiosk 2', 'KIOSK', '18', 'CDC', 1, '%s')""".formatted(BATCH));
    }

    protected void insertAnomaly(String id, String detectedAt, String category, Integer severity) {
        asOwner("""
                INSERT INTO insight.insight_ticketing_anomaly (id, sale_point_id, window_start, window_end, trigger,
                  txn_count, refund_count, refund_ratio, amount_sum, baseline_mean, baseline_stddev, z_score, summary,
                  category, category_confidence, severity, severity_confidence, model_version, enriched_at,
                  enrichment_status, detected_at, batch_id)
                VALUES ('%s', 'SP-0142', TIMESTAMPTZ '%s' - interval '15 minutes', TIMESTAMPTZ '%s', 'REFUND_RATIO',
                  25, 12, 0.4800, 50.00, 14.20, 3.10, 3.48, '{"txnCount": 25}',
                  %s, %s, %s, %s, %s, %s, '%s', TIMESTAMPTZ '%s', '%s')""".formatted(
                        id,
                        detectedAt,
                        detectedAt,
                        category == null ? "NULL" : "'" + category + "'",
                        category == null ? "NULL" : "0.770",
                        severity == null ? "NULL" : severity,
                        severity == null ? "NULL" : "0.690",
                        category == null ? "NULL" : "'jev@0.2.0'",
                        category == null ? "NULL" : "TIMESTAMPTZ '" + detectedAt + "'",
                        category == null ? "PENDING" : "DONE",
                        detectedAt,
                        BATCH));
    }

    /** Runs a query as the schema owner and returns the first column of the first row as text. */
    protected static String ownerText(String sql) {
        try (Connection connection = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run " + sql, e);
        }
    }

    protected static long ownerLong(String sql) {
        return Long.parseLong(ownerText(sql));
    }
}
