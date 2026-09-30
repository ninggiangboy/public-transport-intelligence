package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.RouteDetailReader;
import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.GeoPoint;
import dev.pti.api.transit.domain.GeometrySource;
import dev.pti.api.transit.domain.LineSimplifier;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.api.transit.domain.RouteSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A route with the pattern of each direction (DOC-32 E-02): the most common shape and the stops of a representative
 * trip. The line is simplified once, when the entry is loaded, and kept in the {@code route-detail} cache; a route
 * that is not in the feed is not cached.
 */
@Component
public final class JdbcRouteDetailReader implements RouteDetailReader {

    private static final String ROUTE = "transit/route";
    private static final String PATTERNS = "transit/route_patterns";
    private static final String PATTERN_STOPS = "transit/route_pattern_stops";
    private static final String SHAPE_POINTS = "transit/shape_points";
    private static final String ROUTE_SQL = SqlResources.read(ROUTE);
    private static final String PATTERNS_SQL = SqlResources.read(PATTERNS);
    private static final String PATTERN_STOPS_SQL = SqlResources.read(PATTERN_STOPS);
    private static final String SHAPE_POINTS_SQL = SqlResources.read(SHAPE_POINTS);

    private record Key(long feedVersionId, String routeId) {}

    private record PatternRow(
            int directionId,
            @Nullable String shapeId,
            int tripCount,
            String representativeTripId,
            @Nullable String label,
            @Nullable String headsign) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Key, RouteDetail> cache;

    public JdbcRouteDetailReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("route-detail");
    }

    @Override
    public Optional<RouteDetail> find(ActiveFeed feed, String routeId) {
        // A loader that returns null leaves nothing in the cache, so unknown ids cannot fill it.
        return Optional.ofNullable(
                cache.get(new Key(feed.feedVersionId(), routeId), key -> load(feed.feedVersionId(), routeId)));
    }

    private @Nullable RouteDetail load(long feedVersionId, String routeId) {
        Optional<RouteSummary> route = metrics.time(
                "reader",
                ROUTE,
                () -> jdbc.sql(ROUTE_SQL)
                        .param("fv", feedVersionId)
                        .param("routeId", routeId)
                        .query(JdbcRouteCatalogReader::map)
                        .optional());
        if (route.isEmpty()) {
            return null;
        }
        List<PatternRow> patterns = metrics.time(
                "reader",
                PATTERNS,
                () -> jdbc.sql(PATTERNS_SQL)
                        .param("fv", feedVersionId)
                        .param("routeId", routeId)
                        .query(JdbcRouteDetailReader::pattern)
                        .list());
        List<DirectionPattern> directions = new ArrayList<>();
        for (PatternRow pattern : patterns) {
            directions.add(direction(feedVersionId, pattern));
        }
        return new RouteDetail(feedVersionId, route.get(), directions);
    }

    private DirectionPattern direction(long feedVersionId, PatternRow pattern) {
        List<PatternStop> stops = metrics.time(
                "reader",
                PATTERN_STOPS,
                () -> jdbc.sql(PATTERN_STOPS_SQL)
                        .param("fv", feedVersionId)
                        .param("tripId", pattern.representativeTripId())
                        .query(JdbcRouteDetailReader::stop)
                        .list());
        List<GeoPoint> shape = pattern.shapeId() == null ? List.of() : shapePoints(feedVersionId, pattern.shapeId());
        GeometrySource source = shape.isEmpty() ? GeometrySource.STOPS : GeometrySource.SHAPE;
        List<GeoPoint> line = shape.isEmpty()
                ? stops.stream()
                        .map(stop -> new GeoPoint(stop.lon(), stop.lat()))
                        .toList()
                : shape;
        return new DirectionPattern(
                pattern.directionId(),
                pattern.label(),
                pattern.headsign(),
                pattern.tripCount(),
                pattern.shapeId(),
                source,
                LineSimplifier.simplify(line, LineSimplifier.TOLERANCE_DEGREES),
                stops);
    }

    private List<GeoPoint> shapePoints(long feedVersionId, String shapeId) {
        return metrics.time(
                "reader",
                SHAPE_POINTS,
                () -> jdbc.sql(SHAPE_POINTS_SQL)
                        .param("fv", feedVersionId)
                        .param("shapeId", shapeId)
                        .query((rs, row) -> new GeoPoint(rs.getDouble("lon"), rs.getDouble("lat")))
                        .list());
    }

    private static PatternRow pattern(ResultSet rs, int row) throws SQLException {
        return new PatternRow(
                rs.getInt("direction_id"),
                rs.getString("shape_id"),
                rs.getInt("trip_count"),
                rs.getString("representative_trip_id"),
                rs.getString("label"),
                rs.getString("headsign"));
    }

    private static PatternStop stop(ResultSet rs, int row) throws SQLException {
        return new PatternStop(
                rs.getString("stop_id"),
                rs.getString("stop_code"),
                rs.getString("stop_name"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getInt("stop_sequence"));
    }
}
