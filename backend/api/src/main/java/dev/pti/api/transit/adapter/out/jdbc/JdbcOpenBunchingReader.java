package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.transit.application.port.OpenBunchingReader;
import dev.pti.api.transit.domain.OpenBunching;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The open bunching episodes from {@code insight.insight_bus_bunching} (DOC-32 E-05). The overlay is kept 2 seconds,
 * like the snapshot it is joined to, in the {@code vehicles-live} cache under a key of its own.
 */
@Component
public final class JdbcOpenBunchingReader implements OpenBunchingReader {

    private static final String QUERY = "transit/bunching_open";
    private static final String SQL = SqlResources.read(QUERY);
    private static final String KEY = "bunching-open";

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<String, List<OpenBunching>> cache;

    public JdbcOpenBunchingReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("vehicles-live");
    }

    @Override
    public List<OpenBunching> findOpen() {
        return cache.get(KEY, key -> load());
    }

    private List<OpenBunching> load() {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL).query(JdbcOpenBunchingReader::map).list());
    }

    private static OpenBunching map(ResultSet rs, int row) throws SQLException {
        return new OpenBunching(
                rs.getObject("id", UUID.class),
                rs.getString("vehicle_leader"),
                rs.getString("vehicle_follower"),
                rs.getInt("last_gap_seconds"),
                rs.getInt("scheduled_headway_seconds"));
    }
}
