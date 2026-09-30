package dev.pti.api.transit.adapter.out.jdbc;

import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.transit.application.port.StopDisruptionReader;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.common.events.Audience;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The open disruption alerts of some routes from {@code ops.alert_event} (DOC-32 E-07). Read every time. */
@Component
public final class JdbcStopDisruptionReader implements StopDisruptionReader {

    private static final String QUERY = "transit/stop_disruptions";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcStopDisruptionReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public List<StopDisruption> findOpen(Collection<String> routeIds, Set<Audience> audiences) {
        String[] audienceNames = audiences.stream().map(Audience::name).toArray(String[]::new);
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("routeIds", routeIds.toArray(String[]::new))
                        .param("audiences", audienceNames)
                        .query(JdbcStopDisruptionReader::map)
                        .list());
    }

    private static StopDisruption map(ResultSet rs, int row) throws SQLException {
        return new StopDisruption(
                rs.getString("id"),
                rs.getString("ref_id"),
                rs.getString("route_id"),
                Rows.integer(rs, "direction_id"),
                rs.getInt("severity"),
                rs.getString("title"),
                Rows.instant(rs, "started_at"));
    }
}
