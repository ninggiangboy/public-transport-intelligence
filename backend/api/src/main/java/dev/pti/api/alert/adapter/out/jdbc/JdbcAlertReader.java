package dev.pti.api.alert.adapter.out.jdbc;

import dev.pti.api.alert.application.AlertQuery;
import dev.pti.api.alert.application.port.AlertReader;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors.TimeId;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.events.Audience;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The alert feed from {@code ops.alert_event} (DOC-32 E-20), through the {@code reader}. */
@Component
public final class JdbcAlertReader implements AlertReader {

    private static final String LIST = "alert/alerts_list";
    private static final String LIST_SQL = SqlResources.read(LIST);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final JsonMapper json;

    public JdbcAlertReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper json) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    public Page<Alert> list(AlertQuery query, PageRequest request) {
        TimeId after = TimeIdCursors.parse(request.after());
        List<Alert> rows = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.timestamp(query.from()))
                        .param("to", ResultSets.timestamp(query.to()))
                        .param(
                                "audiences",
                                query.audiences().stream().map(Audience::name).toArray(String[]::new))
                        .param("types", query.types().stream().map(Enum::name).toArray(String[]::new))
                        .param("severities", query.severities().toArray(Integer[]::new))
                        .param("routeIds", query.routeIds().toArray(String[]::new))
                        .param("state", query.state().wireName())
                        .param("since", ResultSets.nullableTimestamp(query.since()))
                        .param("cursorTs", after == null ? null : ResultSets.timestamp(after.time()))
                        .param("cursorId", after == null ? null : after.id().toString())
                        .param("limit", request.fetchSize())
                        .query((rs, row) -> AlertRows.map(rs, json))
                        .list());
        return Page.of(
                request,
                rows,
                row -> TimeIdCursors.of(row.createdAt(), row.id()).keys());
    }
}
