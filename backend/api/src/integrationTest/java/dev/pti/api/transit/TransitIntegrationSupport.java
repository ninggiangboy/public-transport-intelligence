package dev.pti.api.transit;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.db.MigratedDatabases;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A small network in the warehouse for the transit integration tests, put there as the owner of the schema and removed
 * after each test (the database is shared by the test classes of the JVM). The feed has three routes:
 *
 * <ul>
 *   <li>{@code 18}, a bus with three shapes in direction 0 ({@code shA} 2 trips, {@code shB} 4, {@code shC} 4, so
 *       {@code shB} wins on the tie and its stop-richest trip is {@code b2}) and one in direction 1;
 *   <li>{@code 901}, a light rail line;
 *   <li>{@code 77}, a bus whose feed has no shape.
 * </ul>
 *
 * <p>Stops {@code s1 .. s6}, a station {@code st1} and an entrance {@code en1} (never served). The calendar serves
 * every day from 2026-08-23 to 2026-12-12 with service {@code wk}.
 */
public abstract class TransitIntegrationSupport extends ApiIntegrationSupport {

    protected static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Autowired
    protected ApiCaches caches;

    protected long feedVersionId;

    @BeforeEach
    @AfterEach
    void clean() {
        asOwner(
                "DELETE FROM dw.fact_trip_update",
                "DELETE FROM dw.vehicle_position_latest",
                "DELETE FROM dw.dim_vehicle",
                "DELETE FROM insight.insight_eta_prediction",
                "DELETE FROM insight.insight_bus_bunching",
                "DELETE FROM ops.alert_event WHERE dedup_key LIKE 'transit-it-%'",
                "DELETE FROM dw.gtfs_stop_time",
                "DELETE FROM dw.gtfs_trip",
                "DELETE FROM dw.gtfs_shape",
                "DELETE FROM dw.gtfs_calendar",
                "DELETE FROM dw.gtfs_calendar_date",
                "DELETE FROM dw.dim_stop",
                "DELETE FROM dw.dim_route",
                "DELETE FROM dw.dim_agency",
                "DELETE FROM dw.gtfs_feed_version");
        caches.names().forEach(name -> caches.cache(name).invalidateAll());
    }

    /** The ACTIVE feed of the tests, as the API reads it. */
    protected ActiveFeed activeFeed() {
        return new ActiveFeed(
                feedVersionId,
                "2026-08-23",
                CHICAGO,
                LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-12-12"),
                Instant.parse("2026-09-27T08:34:40Z"));
    }

    /** Runs a query as the schema owner and returns the first column of the first row as a number. */
    protected static long ownerLong(String sql) {
        try (Connection connection = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run " + sql, e);
        }
    }

    /** Puts the ACTIVE feed in and returns its id. */
    protected long installFeed() {
        asOwner(activeFeedSql("a", "2026-08-23"));
        feedVersionId = ownerLong("SELECT feed_version_id FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'");
        // The probe looks the feed up every 200 ms and keeps "no feed" for a second: forget what it saw before.
        caches.names().forEach(name -> caches.cache(name).invalidateAll());
        return feedVersionId;
    }

    /** Runs the statements with {@code {fv}} replaced by the id of the feed. */
    protected void asOwnerInFeed(String... statements) {
        String[] resolved = new String[statements.length];
        for (int i = 0; i < statements.length; i++) {
            resolved[i] = statements[i].replace("{fv}", Long.toString(feedVersionId));
        }
        asOwner(resolved);
    }

    /** The feed, routes, stops, calendar, trips, stop times and shapes described in the class comment. */
    protected void installNetwork() {
        installFeed();
        asOwnerInFeed(
                """
                INSERT INTO dw.dim_agency (feed_version_id, agency_id, agency_name, agency_timezone)
                VALUES ({fv}, '0', 'Metro Transit', 'America/Chicago')""",
                """
                INSERT INTO dw.dim_route (feed_version_id, route_id, agency_id, route_short_name, route_long_name,
                  display_name, route_type, route_color, route_text_color, route_sort_order, typical_headway_seconds)
                VALUES
                  ({fv}, '18', '0', '18', 'Nicollet Av - Nicollet Mall - 1st Av', '18', 3, '771473', 'FFFFFF', 24, 600),
                  ({fv}, '901', '0', NULL, 'METRO Blue Line', 'METRO Blue Line', 0, '00539F', 'FFFFFF', 1, NULL),
                  ({fv}, '77', '0', '77', NULL, '77', 3, NULL, NULL, NULL, NULL)""",
                """
                INSERT INTO dw.dim_stop (feed_version_id, stop_id, stop_code, stop_name, lat, lon, location_type,
                  wheelchair_boarding)
                VALUES
                  ({fv}, 's1', '1001', 'Nicollet Ave & 46th St', 44.9204, -93.2780, 0, 1),
                  ({fv}, 's2', '1002', 'Nicollet Ave & 44th St', 44.9240, -93.2780, 0, 1),
                  ({fv}, 's3', '1003', 'Nicollet Ave & Lake St', 44.9480, -93.2780, 0, 0),
                  ({fv}, 's4', '1004', '100% Real_Stop', 44.9600, -93.2700, 0, 2),
                  ({fv}, 's5', '1005', 'Downtown Terminal', 44.9780, -93.2650, 0, 1),
                  ({fv}, 's6', '2001', 'Airport Terminal 1', 44.8800, -93.2100, 0, 1),
                  ({fv}, 'st1', '3001', 'Central Station', 44.9700, -93.2700, 1, 1),
                  ({fv}, 'en1', NULL, 'Central Station Entrance', 44.9701, -93.2701, 2, 0)""",
                """
                INSERT INTO dw.gtfs_calendar (feed_version_id, service_id, monday, tuesday, wednesday, thursday,
                  friday, saturday, sunday, start_date, end_date)
                VALUES ({fv}, 'wk', true, true, true, true, true, true, true, DATE '2026-08-23', DATE '2026-12-12')""",
                """
                INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, direction_label,
                  trip_headsign, shape_id)
                VALUES
                  ({fv}, 'a1', '18', 'wk', 0, 'NB', 'Uptown', 'shA'),
                  ({fv}, 'a2', '18', 'wk', 0, 'NB', 'Uptown', 'shA'),
                  ({fv}, 'b1', '18', 'wk', 0, 'NB', 'Downtown', 'shB'),
                  ({fv}, 'b2', '18', 'wk', 0, 'NB', 'Downtown', 'shB'),
                  ({fv}, 'b3', '18', 'wk', 0, 'NB', 'Downtown', 'shB'),
                  ({fv}, 'b4', '18', 'wk', 0, 'N', 'Uptown', 'shB'),
                  ({fv}, 'c1', '18', 'wk', 0, 'NB', 'Downtown', 'shC'),
                  ({fv}, 'c2', '18', 'wk', 0, 'NB', 'Downtown', 'shC'),
                  ({fv}, 'c3', '18', 'wk', 0, 'NB', 'Downtown', 'shC'),
                  ({fv}, 'c4', '18', 'wk', 0, 'NB', 'Downtown', 'shC'),
                  ({fv}, 'd1', '18', 'wk', 1, 'SB', 'Airport', 'shD'),
                  ({fv}, 'r1', '901', 'wk', 0, 'NB', 'Target Field', 'shR'),
                  ({fv}, 'x1', '77', 'wk', 0, NULL, 'Loop', NULL)""",
                // b2 and b3 both have five stops: b2 wins on the smaller trip id. b1 has three, b4 four.
                """
                INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                  departure_seconds)
                SELECT {fv}, t.trip_id, n, 's' || n, 28800 + n * 120 + t.shift, 28800 + n * 120 + t.shift
                FROM (VALUES ('b1', 3, 0), ('b2', 5, 0), ('b3', 5, 600), ('b4', 4, 1200)) AS t(trip_id, stops, shift)
                CROSS JOIN LATERAL generate_series(1, t.stops) AS n""",
                """
                INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                  departure_seconds)
                VALUES
                  ({fv}, 'a1', 1, 's1', 30000, 30000), ({fv}, 'a2', 1, 's1', 30600, 30600),
                  ({fv}, 'c1', 1, 's1', 31000, 31000), ({fv}, 'c2', 1, 's1', 31200, 31200),
                  ({fv}, 'c3', 1, 's1', 31400, 31400), ({fv}, 'c4', 1, 's1', 31600, 31600),
                  ({fv}, 'd1', 1, 's5', 32400, 32400), ({fv}, 'd1', 2, 's3', 32700, 32700),
                  ({fv}, 'd1', 3, 's1', 33000, 33000),
                  ({fv}, 'r1', 1, 's6', 36000, 36000), ({fv}, 'r1', 2, 'st1', 36600, 36600),
                  ({fv}, 'x1', 1, 's1', 40000, 40000), ({fv}, 'x1', 2, 's2', 40100, 40100),
                  ({fv}, 'x1', 3, 's3', 40200, 40200)""",
                // Shape shB goes 50 steps north then 50 steps east in collinear points: three points are enough.
                """
                INSERT INTO dw.gtfs_shape (feed_version_id, shape_id, shape_pt_sequence, lat, lon)
                SELECT {fv}, 'shB', g, 44.9204 + least(g, 50) * 0.0005, -93.2780 + greatest(g - 50, 0) * 0.0005
                FROM generate_series(0, 100) AS g""",
                """
                INSERT INTO dw.gtfs_shape (feed_version_id, shape_id, shape_pt_sequence, lat, lon)
                VALUES
                  ({fv}, 'shA', 1, 44.92, -93.27), ({fv}, 'shA', 2, 44.93, -93.27),
                  ({fv}, 'shC', 1, 44.90, -93.26), ({fv}, 'shC', 2, 44.91, -93.26),
                  ({fv}, 'shD', 1, 44.978, -93.265), ({fv}, 'shD', 2, 44.95, -93.278), ({fv}, 'shD', 3, 44.9204, -93.278),
                  ({fv}, 'shR', 1, 44.88, -93.21), ({fv}, 'shR', 2, 44.97, -93.27)""");
    }

    /** Makes sure the daily partitions of the trip update fact cover the dates of a test. */
    protected void partitions(String from, String to) {
        asOwner("SELECT dw.ensure_partitions('fact_trip_update', DATE '" + from + "', DATE '" + to + "')");
    }

    /** A {@code fact_trip_update} row for route 18, direction 0, observed, scheduled. */
    protected static String tripUpdate(
            String serviceDate, String tripId, int stopSequence, String scheduledArrivalUtc, Integer delay) {
        return tripUpdate(serviceDate, tripId, stopSequence, "18", 0, scheduledArrivalUtc, delay, true, "SCHEDULED");
    }

    protected static String tripUpdate(
            String serviceDate,
            String tripId,
            int stopSequence,
            String routeId,
            int directionId,
            String scheduledArrivalUtc,
            Integer delay,
            boolean observed,
            String relationship) {
        String arrival =
                delay == null ? "NULL" : "TIMESTAMPTZ '" + scheduledArrivalUtc + "' + interval '" + delay + " seconds'";
        return """
                INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                  vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, departure_time, delay_seconds,
                  is_observed, event_timestamp, payload_hash, batch_id)
                VALUES (DATE '%s', '%s', %d, '%s', %d, 's1', 'v1', '%s', TIMESTAMPTZ '%s', %s, %s, %s, %s,
                  TIMESTAMPTZ '%s', repeat('0', 64), gen_random_uuid())""".formatted(
                        serviceDate,
                        tripId,
                        stopSequence,
                        routeId,
                        directionId,
                        relationship,
                        scheduledArrivalUtc,
                        arrival.equals("NULL") ? "TIMESTAMPTZ '" + scheduledArrivalUtc + "'" : arrival,
                        "NULL",
                        delay == null ? "NULL" : delay.toString(),
                        observed,
                        scheduledArrivalUtc);
    }
}
