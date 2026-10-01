package dev.pti.api.insight.adapter.out.jdbc;

import dev.pti.api.insight.application.TicketingQuery;
import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors.TimeId;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Ticketing anomalies from {@code insight.insight_ticketing_anomaly} (DOC-32 E-15, E-16), through the {@code reader}. */
@Component
public final class JdbcTicketingAnomalyReader implements TicketingAnomalyReader {

    private static final String LIST = "insight/ticketing_list";
    private static final String GET = "insight/ticketing_get";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String GET_SQL = SqlResources.read(GET);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcTicketingAnomalyReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Page<TicketingAnomaly> list(TicketingQuery query, PageRequest request) {
        TimeId after = TimeIdCursors.parse(request.after());
        List<TicketingAnomaly> rows = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.timestamp(query.from()))
                        .param("to", ResultSets.timestamp(query.to()))
                        .param("salePointId", query.salePointId())
                        .param("filterCategory", query.filtersCategory())
                        .param("categories", query.categories().toArray(String[]::new))
                        .param("unclassified", query.unclassified())
                        .param("severities", query.severities().toArray(Integer[]::new))
                        .param("trigger", query.trigger())
                        .param("cursorTs", after == null ? null : ResultSets.timestamp(after.time()))
                        .param("cursorId", after == null ? null : after.id().toString())
                        .param("limit", request.fetchSize())
                        .query((rs, row) -> map(rs, false))
                        .list());
        return Page.of(
                request,
                rows,
                row -> TimeIdCursors.of(row.detectedAt(), row.id()).keys());
    }

    @Override
    public Optional<TicketingAnomaly> find(UUID id) {
        return metrics.time(
                "reader",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id)
                        .query((rs, row) -> map(rs, true))
                        .optional());
    }

    private static TicketingAnomaly map(ResultSet rs, boolean detail) throws SQLException {
        return new TicketingAnomaly(
                ResultSets.uuid(rs, "id"),
                rs.getString("sale_point_id"),
                rs.getString("sale_point_name"),
                rs.getString("route_id"),
                ResultSets.instant(rs, "window_start"),
                ResultSets.instant(rs, "window_end"),
                ResultSets.instant(rs, "detected_at"),
                rs.getString("trigger"),
                rs.getInt("txn_count"),
                rs.getInt("refund_count"),
                rs.getBigDecimal("refund_ratio"),
                rs.getBigDecimal("amount_sum"),
                rs.getBigDecimal("baseline_mean"),
                rs.getBigDecimal("baseline_stddev"),
                rs.getBigDecimal("z_score"),
                rs.getString("enrichment_status"),
                rs.getString("category"),
                rs.getBigDecimal("category_confidence"),
                ResultSets.nullableInt(rs, "severity"),
                rs.getBigDecimal("severity_confidence"),
                rs.getString("model_version"),
                detail ? rs.getString("summary") : null,
                detail ? ResultSets.nullableUuid(rs, "batch_id") : null);
    }
}
