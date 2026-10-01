package dev.pti.api.insight.adapter.out.jdbc;

import dev.pti.api.insight.application.BunchingQuery;
import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.insight.domain.SuggestionRef;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Bunching episodes from {@code insight.insight_bus_bunching} (DOC-32 E-10, E-11), through the {@code reader}. */
@Component
public final class JdbcBunchingReader implements BunchingReader {

    private static final String LIST = "insight/bunching_list";
    private static final String GET = "insight/bunching_get";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String GET_SQL = SqlResources.read(GET);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcBunchingReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Page<BunchingEpisode> list(BunchingQuery query, PageRequest request) {
        TimeId after = TimeIdCursors.parse(request.after());
        List<BunchingEpisode> rows = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.timestamp(query.from()))
                        .param("to", ResultSets.timestamp(query.to()))
                        .param("routeIds", query.routeIds().toArray(String[]::new))
                        .param("status", query.status())
                        .param("cursorTs", after == null ? null : ResultSets.timestamp(after.time()))
                        .param("cursorId", after == null ? null : after.id().toString())
                        .param("limit", request.fetchSize())
                        .query(JdbcBunchingReader::episode)
                        .list());
        return Page.of(
                request,
                rows,
                row -> TimeIdCursors.of(row.episodeStart(), row.id()).keys());
    }

    @Override
    public Optional<BunchingDetail> find(UUID id) {
        return metrics.time(
                "reader",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id)
                        .query((rs, row) -> new BunchingDetail(build(rs, null), SuggestionRows.mapJoined(rs, "s_")))
                        .optional());
    }

    private static BunchingEpisode episode(ResultSet rs, int row) throws SQLException {
        UUID suggestionId = ResultSets.nullableUuid(rs, "suggestion_id");
        return build(
                rs,
                suggestionId == null
                        ? null
                        : new SuggestionRef(
                                suggestionId, rs.getString("action"), rs.getBigDecimal("action_confidence")));
    }

    private static BunchingEpisode build(ResultSet rs, @Nullable SuggestionRef suggestion) throws SQLException {
        return new BunchingEpisode(
                ResultSets.uuid(rs, "id"),
                rs.getString("route_id"),
                rs.getInt("direction_id"),
                rs.getString("vehicle_leader"),
                rs.getString("vehicle_follower"),
                rs.getString("trip_leader"),
                rs.getString("trip_follower"),
                ResultSets.instant(rs, "episode_start"),
                ResultSets.nullableInstant(rs, "episode_end"),
                rs.getString("status"),
                rs.getString("close_reason"),
                rs.getInt("scheduled_headway_seconds"),
                rs.getInt("threshold_seconds"),
                rs.getInt("min_gap_seconds"),
                rs.getInt("last_gap_seconds"),
                rs.getString("open_stop_id"),
                rs.getInt("evaluation_count"),
                ResultSets.instant(rs, "last_evaluated_at"),
                rs.getString("enrichment_status"),
                ResultSets.uuid(rs, "batch_id"),
                suggestion);
    }
}
