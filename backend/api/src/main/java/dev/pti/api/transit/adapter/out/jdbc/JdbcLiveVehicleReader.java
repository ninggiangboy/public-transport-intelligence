package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.LiveVehicleReader;
import dev.pti.api.transit.domain.LiveVehicle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The latest position of every recent vehicle from {@code dw.vehicle_position_latest} (DOC-32 E-05). One snapshot is
 * shared for 2 seconds per set of routes in the {@code vehicles-live} cache, loaded by one caller at a time. The
 * key leaves out the moment and the maximum age: the snapshot is as old as the cache allows, which is the point.
 */
@Component
public final class JdbcLiveVehicleReader implements LiveVehicleReader {

    private static final String QUERY = "transit/vehicles_live";
    private static final String SQL = SqlResources.read(QUERY);

    private record Key(long feedVersionId, Set<String> routeIds, int limit) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Key, List<LiveVehicle>> cache;

    public JdbcLiveVehicleReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("vehicles-live");
    }

    @Override
    public List<LiveVehicle> snapshot(ActiveFeed feed, Set<String> routeIds, Instant now, Duration maxAge, int limit) {
        Key key = new Key(feed.feedVersionId(), new TreeSet<>(routeIds), limit);
        return cache.get(key, k -> load(k, now, maxAge));
    }

    private List<LiveVehicle> load(Key key, Instant now, Duration maxAge) {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("fv", key.feedVersionId())
                        .param("now", Rows.utc(now))
                        .param("maxAgeSeconds", (double) maxAge.toSeconds())
                        .param("routeIds", key.routeIds().toArray(String[]::new))
                        .param("limit", key.limit())
                        .query(JdbcLiveVehicleReader::map)
                        .list());
    }

    private static LiveVehicle map(ResultSet rs, int row) throws SQLException {
        return new LiveVehicle(
                rs.getString("vehicle_id"),
                rs.getString("vehicle_label"),
                rs.getString("route_id"),
                rs.getString("trip_id"),
                rs.getInt("direction_id"),
                rs.getString("trip_headsign"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                Rows.real(rs, "bearing"),
                Rows.real(rs, "speed_mps"),
                rs.getString("current_status"),
                rs.getString("stop_id"),
                rs.getInt("current_stop_sequence"),
                rs.getString("occupancy_status"),
                Rows.requiredInstant(rs, "event_timestamp"),
                Rows.integer(rs, "delay_seconds"),
                Rows.instant(rs, "stop_arrival_at"));
    }
}
