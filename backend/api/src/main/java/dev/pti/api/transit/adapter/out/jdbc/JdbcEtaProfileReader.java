package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.transit.application.port.EtaProfileReader;
import dev.pti.api.transit.domain.EtaRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The historical ETA rows of a route for one weekday and hour from {@code insight.insight_eta_prediction} (DOC-32
 * E-04), kept in the {@code delay-profile} cache. The rows do not depend on the direction, so the direction is not
 * part of the key, but the weekday and hour are: the response differs by them.
 */
@Component
public final class JdbcEtaProfileReader implements EtaProfileReader {

    private static final String QUERY = "transit/route_delay_profile";
    private static final String SQL = SqlResources.read(QUERY);

    private record Key(String routeId, int dayOfWeek, int hourOfDay) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Key, List<EtaRow>> cache;

    public JdbcEtaProfileReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("delay-profile");
    }

    @Override
    public List<EtaRow> read(String routeId, int dayOfWeek, int hourOfDay) {
        return cache.get(new Key(routeId, dayOfWeek, hourOfDay), key -> load(key));
    }

    private List<EtaRow> load(Key key) {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("routeId", key.routeId())
                        .param("dayOfWeek", key.dayOfWeek())
                        .param("hourOfDay", key.hourOfDay())
                        .query(JdbcEtaProfileReader::map)
                        .list());
    }

    private static EtaRow map(ResultSet rs, int row) throws SQLException {
        return new EtaRow(
                rs.getString("stop_id"),
                rs.getBigDecimal("avg_delay_seconds"),
                rs.getInt("median_delay_seconds"),
                rs.getInt("p90_delay_seconds"),
                rs.getInt("sample_count"),
                rs.getObject("window_start", LocalDate.class),
                rs.getObject("window_end", LocalDate.class),
                Rows.requiredInstant(rs, "computed_at"));
    }
}
