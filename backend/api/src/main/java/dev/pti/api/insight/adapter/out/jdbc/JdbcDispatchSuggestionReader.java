package dev.pti.api.insight.adapter.out.jdbc;

import dev.pti.api.insight.application.SuggestionQuery;
import dev.pti.api.insight.application.port.DispatchSuggestionReader;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors.TimeId;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Dispatch suggestions from {@code insight.insight_dispatch_suggestion} (DOC-32 E-17), through the {@code reader}. */
@Component
public final class JdbcDispatchSuggestionReader implements DispatchSuggestionReader {

    private static final String LIST = "insight/dispatch_list";
    private static final String LIST_SQL = SqlResources.read(LIST);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcDispatchSuggestionReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Page<DispatchSuggestion> list(SuggestionQuery query, PageRequest request) {
        TimeId after = TimeIdCursors.parse(request.after());
        UUID bunchingId = query.bunchingId();
        List<DispatchSuggestion> rows = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.timestamp(query.from()))
                        .param("to", ResultSets.timestamp(query.to()))
                        .param("routeIds", query.routeIds().toArray(String[]::new))
                        .param("bunchingId", bunchingId == null ? null : bunchingId.toString())
                        .param("feedback", query.feedback())
                        .param("cursorTs", after == null ? null : ResultSets.timestamp(after.time()))
                        .param("cursorId", after == null ? null : after.id().toString())
                        .param("limit", request.fetchSize())
                        .query((rs, row) -> SuggestionRows.map(rs, ""))
                        .list());
        return Page.of(
                request,
                rows,
                row -> TimeIdCursors.of(row.createdAt(), row.id()).keys());
    }
}
