package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.StopRoutesReader;
import dev.pti.api.transit.domain.StopRouteRef;
import dev.pti.api.transit.domain.StopRoutes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which routes call at which stops (DOC-32 E-06, E-07), built from the stop times of a feed version. The query reads
 * the whole feed and takes about two seconds, so the result is built once per feed and kept in the {@code
 * stop-routes} cache until the feed changes.
 */
@Component
public final class JdbcStopRoutesReader implements StopRoutesReader {

    private static final String QUERY = "transit/stop_routes";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Long, StopRoutes> cache;

    public JdbcStopRoutesReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("stop-routes");
    }

    @Override
    public StopRoutes read(ActiveFeed feed) {
        return cache.get(feed.feedVersionId(), feedVersionId -> load(feedVersionId));
    }

    private StopRoutes load(long feedVersionId) {
        // stop -> route -> headsigns, sorted so that the response does not depend on the order the rows arrive in.
        Map<String, Map<String, TreeSet<String>>> byStop = new HashMap<>();
        metrics.time("reader", QUERY, () -> {
            jdbc.sql(SQL).param("fv", feedVersionId).query(rs -> {
                TreeSet<String> headsigns = byStop.computeIfAbsent(rs.getString("stop_id"), stop -> new TreeMap<>())
                        .computeIfAbsent(rs.getString("route_id"), route -> new TreeSet<>());
                String headsign = rs.getString("trip_headsign");
                if (headsign != null) {
                    headsigns.add(headsign);
                }
            });
            return null;
        });
        Map<String, List<StopRouteRef>> refs = new HashMap<>();
        byStop.forEach((stopId, routes) -> {
            List<StopRouteRef> list = new ArrayList<>();
            routes.forEach((routeId, headsigns) -> list.add(new StopRouteRef(routeId, List.copyOf(headsigns))));
            refs.put(stopId, list);
        });
        return new StopRoutes(refs);
    }
}
