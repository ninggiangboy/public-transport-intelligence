package dev.pti.analytics.reference.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.pti.analytics.reference.application.port.ActiveFeedVersion;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.StopTime;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.DayType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AnalyticsReferenceCache} on SQL and Caffeine (DOC-23 §3 notes). Everything it holds belongs to one feed
 * version: the routes, headways and {@code dim_date} are loaded together when the version is first needed, and the
 * trip patterns of a route when a detector first asks for one of its trips. When {@link ActiveFeedVersion} reports
 * another version, all of it is dropped and loaded again.
 *
 * <p>Queries name the feed version instead of reading the {@code *_current} views, so what is cached always matches
 * the version it is filed under, also while a new feed is being activated.
 */
public class JdbcAnalyticsReferenceCache implements AnalyticsReferenceCache {

    private static final int MAX_CACHED_ROUTES = 512;

    private static final String ZONE = "SELECT agency_timezone FROM dw.gtfs_feed_version WHERE feed_version_id = :v";

    private static final String ROUTES = """
            SELECT route_id, route_type, coalesce(nullif(btrim(route_short_name), ''), route_id) AS label
            FROM dw.dim_route
            WHERE feed_version_id = :v""";

    private static final String DIRECTION_LABELS = """
            SELECT DISTINCT ON (route_id, direction_id) route_id, direction_id, direction_label
            FROM (SELECT route_id, direction_id, direction_label, count(*) AS trips
                  FROM dw.gtfs_trip
                  WHERE feed_version_id = :v AND nullif(btrim(direction_label), '') IS NOT NULL
                  GROUP BY route_id, direction_id, direction_label) labelled
            ORDER BY route_id, direction_id, trips DESC, direction_label""";

    private static final String HEADWAYS = """
            SELECT route_id, direction_id, day_type, hour_of_day, scheduled_headway_seconds
            FROM dw.route_headway
            WHERE feed_version_id = :v AND scheduled_headway_seconds IS NOT NULL""";

    private static final String DAY_TYPES = "SELECT date, day_type FROM dw.dim_date";

    private static final String TRIP_ROUTES = "SELECT trip_id, route_id FROM dw.gtfs_trip WHERE feed_version_id = :v";

    private static final String ROUTE_TRIPS = """
            SELECT t.trip_id, t.direction_id, st.stop_sequence, st.stop_id, st.arrival_seconds,
                   st.departure_seconds, st.shape_dist_traveled, s.lat, s.lon
            FROM dw.gtfs_trip t
            JOIN dw.gtfs_stop_time st ON st.feed_version_id = t.feed_version_id AND st.trip_id = t.trip_id
            JOIN dw.dim_stop s ON s.feed_version_id = st.feed_version_id AND s.stop_id = st.stop_id
            WHERE t.feed_version_id = :v AND t.route_id = :routeId
            ORDER BY t.trip_id, st.stop_sequence""";

    private final JdbcClient jdbc;
    private final ActiveFeedVersion activeFeed;
    private volatile @Nullable Snapshot snapshot;

    public JdbcAnalyticsReferenceCache(JdbcClient jdbc, ActiveFeedVersion activeFeed) {
        this.jdbc = jdbc;
        this.activeFeed = activeFeed;
    }

    @Override
    public boolean hasActiveFeed() {
        return activeFeed.current().isPresent();
    }

    @Override
    public long feedVersionId() {
        return snapshot().version;
    }

    @Override
    public ZoneId agencyZone() {
        return snapshot().zone;
    }

    @Override
    public Optional<RouteInfo> route(String routeId) {
        return Optional.ofNullable(snapshot().routes.get(routeId));
    }

    @Override
    public Optional<TripPattern> trip(String tripId) {
        Snapshot current = snapshot();
        String routeId = current.tripRoutes().get(tripId);
        if (routeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.patterns.get(routeId).get(tripId));
    }

    @Override
    public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hourOfServiceDay) {
        Integer seconds = snapshot().headways.get(new HeadwayKey(routeId, directionId, dayType, hourOfServiceDay));
        return seconds == null ? OptionalInt.empty() : OptionalInt.of(seconds);
    }

    @Override
    public DayType dayType(LocalDate serviceDate) {
        DayType type = snapshot().dayTypes.get(serviceDate);
        return type == null ? DayType.of(serviceDate) : type;
    }

    /** The snapshot of the ACTIVE version, loaded now when the version changed since the last call. */
    private Snapshot snapshot() {
        OptionalLong active = activeFeed.current();
        if (active.isEmpty()) {
            throw new IllegalStateException("No GTFS feed is ACTIVE");
        }
        Snapshot held = snapshot;
        if (held != null && held.version == active.getAsLong()) {
            return held;
        }
        synchronized (this) {
            held = snapshot;
            if (held == null || held.version != active.getAsLong()) {
                held = load(active.getAsLong());
                snapshot = held;
            }
            return held;
        }
    }

    private Snapshot load(long version) {
        ZoneId zone =
                ZoneId.of(jdbc.sql(ZONE).param("v", version).query(String.class).single());
        return new Snapshot(
                version,
                zone,
                loadRoutes(version),
                loadHeadways(version),
                loadDayTypes(),
                this::loadTripRoutes,
                this::loadPatterns);
    }

    private Map<String, RouteInfo> loadRoutes(long version) {
        Map<String, Map<Integer, String>> labels = new HashMap<>();
        jdbc.sql(DIRECTION_LABELS).param("v", version).query(rs -> {
            labels.computeIfAbsent(rs.getString("route_id"), r -> new HashMap<>())
                    .put(rs.getInt("direction_id"), rs.getString("direction_label"));
        });
        Map<String, RouteInfo> routes = new HashMap<>();
        jdbc.sql(ROUTES).param("v", version).query(rs -> {
            String routeId = rs.getString("route_id");
            Map<Integer, String> byDirection = new HashMap<>(labels.getOrDefault(routeId, Map.of()));
            byDirection.putIfAbsent(0, "Direction 0");
            byDirection.putIfAbsent(1, "Direction 1");
            routes.put(routeId, new RouteInfo(routeId, rs.getInt("route_type"), rs.getString("label"), byDirection));
        });
        return Map.copyOf(routes);
    }

    private Map<HeadwayKey, Integer> loadHeadways(long version) {
        Map<HeadwayKey, Integer> headways = new HashMap<>();
        jdbc.sql(HEADWAYS).param("v", version).query(rs -> {
            headways.put(
                    new HeadwayKey(
                            rs.getString("route_id"),
                            rs.getInt("direction_id"),
                            DayType.valueOf(rs.getString("day_type")),
                            rs.getInt("hour_of_day")),
                    rs.getInt("scheduled_headway_seconds"));
        });
        return Map.copyOf(headways);
    }

    private Map<LocalDate, DayType> loadDayTypes() {
        Map<LocalDate, DayType> types = new HashMap<>();
        jdbc.sql(DAY_TYPES).query(rs -> {
            types.put(rs.getObject("date", LocalDate.class), DayType.valueOf(rs.getString("day_type")));
        });
        return Map.copyOf(types);
    }

    private Map<String, String> loadTripRoutes(long version) {
        Map<String, String> routes = new HashMap<>();
        jdbc.sql(TRIP_ROUTES).param("v", version).query(rs -> {
            routes.put(rs.getString("trip_id"), rs.getString("route_id"));
        });
        return Map.copyOf(routes);
    }

    /** All trips of a route with their stop patterns, by trip id. */
    private Map<String, TripPattern> loadPatterns(long version, String routeId) {
        Map<String, List<StopTime>> stopTimes = new LinkedHashMap<>();
        Map<String, Integer> directions = new HashMap<>();
        jdbc.sql(ROUTE_TRIPS).param("v", version).param("routeId", routeId).query(rs -> {
            String tripId = rs.getString("trip_id");
            directions.putIfAbsent(tripId, rs.getInt("direction_id"));
            stopTimes.computeIfAbsent(tripId, t -> new ArrayList<>()).add(stopTime(rs));
        });
        Map<String, TripPattern> patterns = new HashMap<>();
        stopTimes.forEach((tripId, rows) ->
                patterns.put(tripId, TripPattern.fromStopTimes(tripId, routeId, directions.get(tripId), rows)));
        return Map.copyOf(patterns);
    }

    private static StopTime stopTime(ResultSet rs) throws SQLException {
        double shapeDist = rs.getDouble("shape_dist_traveled");
        Double shape = rs.wasNull() ? null : shapeDist;
        return new StopTime(
                rs.getInt("stop_sequence"),
                rs.getString("stop_id"),
                rs.getInt("arrival_seconds"),
                rs.getInt("departure_seconds"),
                shape,
                nanIfNull(rs, "lat"),
                nanIfNull(rs, "lon"));
    }

    private static double nanIfNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    private record HeadwayKey(String routeId, int directionId, DayType dayType, int hourOfServiceDay) {}

    /** Loads the trip-to-route map of a version. */
    @FunctionalInterface
    private interface TripRouteLoader {
        Map<String, String> load(long version);
    }

    /** Loads the trips of one route in a version. */
    @FunctionalInterface
    private interface PatternLoader {
        Map<String, TripPattern> load(long version, String routeId);
    }

    /** Everything of one feed version. */
    private static final class Snapshot {

        final long version;
        final ZoneId zone;
        final Map<String, RouteInfo> routes;
        final Map<HeadwayKey, Integer> headways;
        final Map<LocalDate, DayType> dayTypes;
        final LoadingCache<String, Map<String, TripPattern>> patterns;
        private final TripRouteLoader tripRouteLoader;
        private volatile @Nullable Map<String, String> tripRoutes;

        Snapshot(
                long version,
                ZoneId zone,
                Map<String, RouteInfo> routes,
                Map<HeadwayKey, Integer> headways,
                Map<LocalDate, DayType> dayTypes,
                TripRouteLoader tripRouteLoader,
                PatternLoader patternLoader) {
            this.version = version;
            this.zone = zone;
            this.routes = routes;
            this.headways = headways;
            this.dayTypes = dayTypes;
            this.tripRouteLoader = tripRouteLoader;
            this.patterns = Caffeine.newBuilder()
                    .maximumSize(MAX_CACHED_ROUTES)
                    .build(routeId -> patternLoader.load(version, routeId));
        }

        /** The route of each trip, loaded with the first lookup of a trip. */
        Map<String, String> tripRoutes() {
            Map<String, String> loaded = tripRoutes;
            if (loaded == null) {
                synchronized (this) {
                    loaded = tripRoutes;
                    if (loaded == null) {
                        loaded = tripRouteLoader.load(version);
                        tripRoutes = loaded;
                    }
                }
            }
            return loaded;
        }
    }
}
