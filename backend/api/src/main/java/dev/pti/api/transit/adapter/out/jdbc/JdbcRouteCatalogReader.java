package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.api.transit.domain.RouteSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The routes of a feed version from {@code dw.dim_route} (DOC-32 E-01), kept in the {@code routes} cache. */
@Component
public final class JdbcRouteCatalogReader implements RouteCatalogReader {

    private static final String QUERY = "transit/routes";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Long, RouteCatalog> cache;

    public JdbcRouteCatalogReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("routes");
    }

    @Override
    public RouteCatalog read(ActiveFeed feed) {
        return cache.get(feed.feedVersionId(), feedVersionId -> load(feedVersionId));
    }

    private RouteCatalog load(long feedVersionId) {
        return metrics.time(
                "reader",
                QUERY,
                () -> new RouteCatalog(
                        feedVersionId,
                        jdbc.sql(SQL)
                                .param("fv", feedVersionId)
                                .query(JdbcRouteCatalogReader::map)
                                .list()));
    }

    /** Also the row of {@code transit/route}, which selects the same columns. */
    static RouteSummary map(ResultSet rs, int row) throws SQLException {
        return new RouteSummary(
                rs.getString("route_id"),
                rs.getString("route_short_name"),
                rs.getString("route_long_name"),
                rs.getString("display_name"),
                rs.getInt("route_type"),
                rs.getString("route_color"),
                rs.getString("route_text_color"),
                Rows.integer(rs, "route_sort_order"),
                Rows.integer(rs, "typical_headway_seconds"));
    }
}
