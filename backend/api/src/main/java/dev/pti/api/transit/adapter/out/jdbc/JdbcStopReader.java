package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.Stop;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The stops and stations of a feed version from {@code dw.dim_stop} (DOC-32 E-06, E-07). One stop is kept in the
 * {@code stop-detail} cache (a stop that does not exist is not), a search result 60 seconds in {@code stops-search}.
 */
@Component
public final class JdbcStopReader implements StopReader {

    private static final String STOP = "transit/stop";
    private static final String SEARCH = "transit/stops_search";
    private static final String AREA = "transit/stops_bbox";
    private static final String STOP_SQL = SqlResources.read(STOP);
    private static final String SEARCH_SQL = SqlResources.read(SEARCH);
    private static final String AREA_SQL = SqlResources.read(AREA);

    private record StopKey(long feedVersionId, String stopId) {}

    private record TextKey(long feedVersionId, String q, int limit) {}

    /** What the window/route query depends on; the stops of the route follow from the route and the feed. */
    private record AreaKey(
            long feedVersionId,
            @Nullable BoundingBox bbox,
            @Nullable String routeId,
            @Nullable String afterStopId,
            int fetchSize) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<StopKey, Stop> stopCache;
    private final Cache<Object, List<Stop>> searchCache;

    public JdbcStopReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.stopCache = caches.cache("stop-detail");
        this.searchCache = caches.cache("stops-search");
    }

    @Override
    public Optional<Stop> find(ActiveFeed feed, String stopId) {
        // A loader that returns null leaves nothing in the cache, so unknown ids cannot fill it.
        return Optional.ofNullable(stopCache.get(new StopKey(feed.feedVersionId(), stopId), key -> loadStop(key)));
    }

    @Override
    public List<Stop> searchText(ActiveFeed feed, String q, int limit) {
        return searchCache.get(new TextKey(feed.feedVersionId(), q, limit), key -> loadText((TextKey) key));
    }

    @Override
    public List<Stop> searchArea(ActiveFeed feed, AreaQuery query) {
        AreaKey key = new AreaKey(
                feed.feedVersionId(), query.bbox(), query.routeId(), query.afterStopId(), query.fetchSize());
        return searchCache.get(key, k -> loadArea((AreaKey) k, query));
    }

    private @Nullable Stop loadStop(StopKey key) {
        return metrics.time(
                "reader",
                STOP,
                () -> jdbc.sql(STOP_SQL)
                        .param("fv", key.feedVersionId())
                        .param("stopId", key.stopId())
                        .query(JdbcStopReader::map)
                        .optional()
                        .orElse(null));
    }

    private List<Stop> loadText(TextKey key) {
        String escaped = key.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return metrics.time(
                "reader",
                SEARCH,
                () -> jdbc.sql(SEARCH_SQL)
                        .param("fv", key.feedVersionId())
                        .param("q", key.q())
                        .param("qPrefix", escaped + "%")
                        .param("qContains", "%" + escaped + "%")
                        .param("limit", key.limit())
                        .query(JdbcStopReader::map)
                        .list());
    }

    private List<Stop> loadArea(AreaKey key, AreaQuery query) {
        BoundingBox bbox = key.bbox();
        List<String> routeStopIds = query.routeStopIds();
        return metrics.time(
                "reader",
                AREA,
                () -> jdbc.sql(AREA_SQL)
                        .param("fv", key.feedVersionId())
                        .param("minLon", bbox != null ? bbox.minLon() : null)
                        .param("minLat", bbox != null ? bbox.minLat() : null)
                        .param("maxLon", bbox != null ? bbox.maxLon() : null)
                        .param("maxLat", bbox != null ? bbox.maxLat() : null)
                        .param("stopIds", routeStopIds != null ? routeStopIds.toArray(String[]::new) : null)
                        .param("afterStopId", key.afterStopId())
                        .param("limit", key.fetchSize())
                        .query(JdbcStopReader::map)
                        .list());
    }

    private static Stop map(ResultSet rs, int row) throws SQLException {
        return new Stop(
                rs.getString("stop_id"),
                rs.getString("stop_code"),
                rs.getString("stop_name"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getInt("location_type"),
                rs.getInt("wheelchair_boarding"));
    }
}
