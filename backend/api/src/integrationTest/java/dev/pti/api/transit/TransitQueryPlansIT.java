package dev.pti.api.transit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.common.gtfs.GtfsTime;
import dev.pti.db.MigratedDatabases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The plans of the heavy transit queries on a warehouse with the volume DOC-32 plans for (NFR-10): seven days of trip
 * updates for 130 routes (273,000 rows), 2,000 stops and trips, 60,000 stop times, 606 live vehicles, the ETA table of
 * ten routes. Each query is run as {@code api_reader} through {@code EXPLAIN (ANALYZE, BUFFERS)}; the plans are written
 * to {@code build/query-plans/transit.txt} for the architect's review of the p95 targets, and the test asserts that the
 * indexes DOC-32 names are the ones used.
 */
class TransitQueryPlansIT extends TransitIntegrationSupport {

    private static final Path REPORT = Path.of("build", "query-plans", "transit.txt");

    private NamedParameterJdbcTemplate reader() {
        return new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"), "api_reader", MigratedDatabases.password("api_reader")));
    }

    private void seed() {
        installFeed();
        partitions(
                LocalDate.now().minusDays(8).toString(),
                LocalDate.now().plusDays(1).toString());
        asOwnerInFeed("""
                INSERT INTO dw.dim_agency (feed_version_id, agency_id, agency_name, agency_timezone)
                VALUES ({fv}, '0', 'Metro Transit', 'America/Chicago')""", """
                INSERT INTO dw.dim_route (feed_version_id, route_id, agency_id, display_name, route_type,
                  route_sort_order)
                SELECT {fv}, 'R' || lpad(n::text, 3, '0'), '0', 'R' || n, 3, n FROM generate_series(1, 130) n""", """
                INSERT INTO dw.dim_stop (feed_version_id, stop_id, stop_code, stop_name, lat, lon)
                SELECT {fv}, 'S' || lpad((n - 1)::text, 4, '0'), (1000 + n)::text, 'Stop ' || n,
                       44.80 + (n % 50) * 0.004, -93.40 + (n / 50) * 0.005
                FROM generate_series(1, 2000) n""", """
                INSERT INTO dw.gtfs_calendar (feed_version_id, service_id, monday, tuesday, wednesday, thursday, friday,
                  saturday, sunday, start_date, end_date)
                VALUES ({fv}, 'wk', true, true, true, true, true, true, true, current_date - 60, current_date + 60)""", """
                INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, trip_headsign)
                SELECT {fv}, 'T' || i, 'R' || lpad((1 + i % 130)::text, 3, '0'), 'wk', i % 2, 'Head ' || (i % 20)
                FROM generate_series(1, 2000) i""", """
                INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, trip_headsign)
                SELECT {fv}, 'X' || i, 'R001', 'wk', 0, 'Busy stop' FROM generate_series(1, 300) i""", """
                INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                  departure_seconds)
                SELECT {fv}, 'T' || i, n, 'S' || lpad(((i * 13 + n * 7) % 2000)::text, 4, '0'),
                       18000 + n * 120 + (i % 400) * 120, 18000 + n * 120 + (i % 400) * 120
                FROM generate_series(1, 2000) i CROSS JOIN generate_series(1, 30) n
                ON CONFLICT DO NOTHING""", """
                INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                  departure_seconds)
                SELECT {fv}, 'X' || i, 1, 'S0000', 18000 + i * 200, 18000 + i * 200 FROM generate_series(1, 300) i""");
        asOwner(
                """
                INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                  vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, delay_seconds, is_observed,
                  event_timestamp, payload_hash, batch_id)
                SELECT d.service_date, r.route_id || '-' || k, 1, r.route_id, k % 2,
                       'S' || lpad((k % 2000)::text, 4, '0'), 'V' || (k % 600), 'SCHEDULED', x.ts,
                       x.ts + make_interval(secs => x.delay), x.delay, true, x.ts, repeat('0', 64), gen_random_uuid()
                FROM (SELECT current_date - g AS service_date FROM generate_series(0, 6) g) d
                CROSS JOIN (SELECT 'R' || lpad(n::text, 3, '0') AS route_id FROM generate_series(1, 130) n) r
                CROSS JOIN generate_series(0, 299) k
                CROSS JOIN LATERAL (SELECT ((d.service_date + time '05:00') AT TIME ZONE 'America/Chicago')
                                           + k * interval '3 minutes' AS ts, ((k * 37) % 400 - 100) AS delay) x""",
                """
                INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id, direction_id, lat,
                  lon, current_stop_sequence, stop_id, current_status, event_timestamp, batch_id)
                SELECT 'V' || n, current_date, 'R' || lpad((1 + n % 130)::text, 3, '0'),
                       'R' || lpad((1 + n % 130)::text, 3, '0') || '-' || (n % 300), n % 2, 44.9, -93.2, 1,
                       'S0001', 'IN_TRANSIT_TO', now() - (n % 60) * interval '1 second', gen_random_uuid()
                FROM generate_series(1, 606) n""",
                """
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                SELECT 'R' || lpad(r::text, 3, '0'), 'S' || lpad(s::text, 4, '0'), d, h, 60.0, 50, 170, 20,
                       current_date - 28, current_date - 1, now(), gen_random_uuid()
                FROM generate_series(1, 10) r CROSS JOIN generate_series(0, 99) s
                CROSS JOIN generate_series(1, 7) d CROSS JOIN generate_series(0, 23) h""",
                """
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, episode_end, status, close_reason,
                  scheduled_headway_seconds, threshold_seconds, min_gap_seconds, last_gap_seconds, last_evaluated_at,
                  batch_id)
                SELECT gen_random_uuid(), 'R' || lpad((1 + n % 130)::text, 3, '0'), 0, 'L' || n, 'F' || n, 't', 't2',
                       now() - n * interval '1 hour', CASE WHEN n > 10 THEN now() - n * interval '1 hour' + interval '5 minutes' END,
                       CASE WHEN n > 10 THEN 'CLOSED' ELSE 'OPEN' END,
                       CASE WHEN n > 10 THEN 'GAP_RECOVERED' END, 600, 300, 100, 120, now(), gen_random_uuid()
                FROM generate_series(1, 2000) n""",
                """
                INSERT INTO ops.alert_event (id, type, severity, audience, route_id, title, dedup_key, created_at,
                  resolved_at)
                SELECT gen_random_uuid(), 'DISRUPTION', 1, 'PUBLIC', 'R' || lpad((1 + n % 130)::text, 3, '0'), 'Alert',
                       'transit-it-plan-' || n, now() - n * interval '1 minute',
                       CASE WHEN n > 20 THEN now() END
                FROM generate_series(1, 2000) n""",
                "ANALYZE dw.fact_trip_update, dw.gtfs_stop_time, dw.gtfs_trip, dw.dim_stop, dw.dim_route, "
                        + "dw.vehicle_position_latest, insight.insight_eta_prediction, insight.insight_bus_bunching, "
                        + "ops.alert_event");
    }

    private final StringBuilder report = new StringBuilder();

    /** Runs the plan twice (the first run warms the cache) and keeps the second. */
    private String explain(String name, String sql, Map<String, Object> params) {
        NamedParameterJdbcTemplate template = reader();
        MapSqlParameterSource source = new MapSqlParameterSource(params);
        template.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, source, String.class);
        List<String> lines = template.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, source, String.class);
        String plan = String.join("\n", lines);
        report.append("=== ").append(name).append('\n').append(plan).append("\n\n");
        return plan;
    }

    private static double executionMillis(String plan) {
        String marker = "Execution Time: ";
        int at = plan.lastIndexOf(marker);
        return Double.parseDouble(plan.substring(at + marker.length(), plan.indexOf(" ms", at)));
    }

    @Test
    @DisplayName("Plans of E-02, E-03, E-04, E-05, E-06, E-07 and E-08 at the volume of 7 days of data")
    void plans() throws IOException {
        seed();
        Instant now = Instant.now();
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDate today = now.atZone(CHICAGO).toLocalDate();
        String fv = Long.toString(feedVersionId);

        // E-03, hour buckets over the default 7 days.
        Map<String, Object> delays = new LinkedHashMap<>();
        delays.put("tz", "America/Chicago");
        delays.put("routeId", "R001");
        delays.put("fromDate", today.minusDays(7));
        delays.put("toDate", today);
        delays.put("from", nowUtc.minusDays(7));
        delays.put("to", nowUtc);
        delays.put("directionId", null);
        delays.put("early", 300);
        delays.put("late", 300);
        String delaySql = SqlResources.read("transit/route_delays");
        String hourly = explain(
                "E-03 route_delays, bucket=hour, 7 days",
                delaySql.replace("{{bucket}}", "date_trunc('hour', tu.scheduled_arrival, 'UTC')"),
                delays);
        String weekly = explain(
                "E-03 route_delays, bucket=hour-of-week, 7 days",
                delaySql.replace(
                        "{{bucket}}",
                        "(extract(isodow FROM tu.scheduled_arrival AT TIME ZONE :tz)::int * 100"
                                + " + extract(hour FROM tu.scheduled_arrival AT TIME ZONE :tz)::int)"),
                delays);

        // E-04, the profile of one route.
        String profile = explain(
                "E-04 route_delay_profile",
                SqlResources.read("transit/route_delay_profile"),
                Map.of("routeId", "R001", "dayOfWeek", 2, "hourOfDay", 16));

        // E-05, the live snapshot of 606 vehicles.
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("fv", feedVersionId);
        live.put("now", nowUtc);
        live.put("maxAgeSeconds", 300.0);
        live.put("routeIds", new String[0]);
        live.put("limit", 1501);
        String vehicles = explain("E-05 vehicles_live", SqlResources.read("transit/vehicles_live"), live);
        String bunching = explain("E-05 bunching_open", SqlResources.read("transit/bunching_open"), Map.of());

        // E-08, the arrivals at a stop with about 330 calls a day.
        Instant windowStart = now.minus(Duration.ofMinutes(30));
        Instant windowEnd = now.plus(Duration.ofMinutes(90));
        Map<String, Object> arrivals = new LinkedHashMap<>();
        arrivals.put("fv", feedVersionId);
        arrivals.put("stopId", "S0000");
        arrivals.put("tz", "America/Chicago");
        arrivals.put("today", today);
        arrivals.put("now", nowUtc);
        arrivals.put("horizonSeconds", 5400.0);
        arrivals.put("secFromYesterday", seconds(today.minusDays(1), windowStart));
        arrivals.put("secToYesterday", seconds(today.minusDays(1), windowEnd));
        arrivals.put("secFromToday", seconds(today, windowStart));
        arrivals.put("secToToday", seconds(today, windowEnd));
        String arrivalsPlan = explain("E-08 arrivals", SqlResources.read("transit/arrivals"), arrivals);

        // E-02, E-06, E-07.
        String patterns = explain(
                "E-02 route_patterns",
                SqlResources.read("transit/route_patterns"),
                Map.of("fv", feedVersionId, "routeId", "R001"));
        String disruptions = explain(
                "E-07 stop_disruptions",
                SqlResources.read("transit/stop_disruptions"),
                Map.of(
                        "routeIds", new String[] {"R001", "R002", "R003"},
                        "audiences", new String[] {"PUBLIC"}));
        String stopRoutes =
                explain("E-06 stop_routes", SqlResources.read("transit/stop_routes"), Map.of("fv", feedVersionId));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("fv", feedVersionId);
        window.put("minLon", -93.30);
        window.put("minLat", 44.80);
        window.put("maxLon", -93.20);
        window.put("maxLat", 45.00);
        window.put("stopIds", null);
        window.put("afterStopId", null);
        window.put("limit", 201);
        String bbox = explain("E-06 stops_bbox", SqlResources.read("transit/stops_bbox"), window);
        String search = explain(
                "E-06 stops_search",
                SqlResources.read("transit/stops_search"),
                Map.of(
                        "fv", feedVersionId,
                        "q", "Stop 12",
                        "qPrefix", "Stop 12%",
                        "qContains", "%Stop 12%",
                        "limit", 20));

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString());
        System.out.println(report);

        // The indexes DOC-32 and DOC-14 name.
        assertThat(hourly).contains("_service_date_route_id_idx");
        assertThat(weekly).contains("_service_date_route_id_idx");
        assertThat(profile).contains("insight_eta_prediction_pkey");
        assertThat(vehicles).contains("fact_trip_update_p").contains("pkey");
        assertThat(bunching).contains("insight_bus_bunching_open_idx");
        assertThat(arrivalsPlan).contains("gtfs_stop_time_stop_idx").contains("insight_eta_prediction_pkey");
        assertThat(arrivalsPlan).contains("day_of_week = ").contains("hour_of_day = ");
        assertThat(patterns).contains("gtfs_trip_route_idx");
        assertThat(disruptions).contains("alert_event_open_idx");

        // The targets of DOC-32 §2 (p95 at background load); a single warm run here is well under them.
        assertThat(executionMillis(hourly)).as("E-03 hour").isLessThan(200);
        assertThat(executionMillis(weekly)).as("E-03 hour-of-week").isLessThan(200);
        assertThat(executionMillis(profile)).as("E-04").isLessThan(50);
        assertThat(executionMillis(vehicles)).as("E-05").isLessThan(100);
        assertThat(executionMillis(arrivalsPlan)).as("E-08").isLessThan(150);
        assertThat(executionMillis(patterns)).as("E-02").isLessThan(50);
        assertThat(executionMillis(bbox)).as("E-06 bbox").isLessThan(80);
        assertThat(executionMillis(search)).as("E-06 q").isLessThan(80);
        assertThat(executionMillis(stopRoutes))
                .as("stop-routes map, once per feed")
                .isLessThan(2000);
    }

    private static int seconds(LocalDate serviceDate, Instant instant) {
        return (int) Duration.between(GtfsTime.toInstant(serviceDate, 0, CHICAGO), instant)
                .toSeconds();
    }
}
