package dev.pti.api.insight.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.insight.application.DisruptionQuery;
import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors.TimeId;
import dev.pti.api.platform.domain.KeysetCursor;
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

/**
 * Disruption episodes with their alert from {@code insight.insight_service_disruption} and {@code ops.alert_event}
 * (DOC-32 E-12, E-13), through the {@code reader}. The public view, which anyone may ask for and which every passenger
 * screen polls, is kept 5 seconds in the {@code public-disruptions} cache (DOC-31 §10.3), loaded once per key.
 */
@Component
public final class JdbcDisruptionReader implements DisruptionReader {

    private static final String LIST = "insight/disruption_list";
    private static final String GET = "insight/disruption_get";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String GET_SQL = SqlResources.read(GET);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<String, Page<DisruptionEpisode>> publicPages;

    public JdbcDisruptionReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.publicPages = caches.cache("public-disruptions");
    }

    @Override
    public Page<DisruptionEpisode> list(DisruptionQuery query, PageRequest request) {
        if (!query.publicOnly()) {
            return load(query, request);
        }
        return publicPages.get(cacheKey(query, request), key -> load(query, request));
    }

    private static String cacheKey(DisruptionQuery query, PageRequest request) {
        return String.join(
                "|",
                query.from().toString(),
                query.to().toString(),
                String.join(",", query.routeIds()),
                String.valueOf(query.status()),
                cursorKeys(request),
                String.valueOf(request.limit()));
    }

    private static String cursorKeys(PageRequest request) {
        KeysetCursor after = request.after();
        return after == null ? "" : String.join(",", after.keys());
    }

    private Page<DisruptionEpisode> load(DisruptionQuery query, PageRequest request) {
        TimeId after = TimeIdCursors.parse(request.after());
        List<DisruptionEpisode> rows = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.timestamp(query.from()))
                        .param("to", ResultSets.timestamp(query.to()))
                        .param("routeIds", query.routeIds().toArray(String[]::new))
                        .param("status", query.status())
                        .param("publicOnly", query.publicOnly())
                        .param("cursorTs", after == null ? null : ResultSets.timestamp(after.time()))
                        .param("cursorId", after == null ? null : after.id().toString())
                        .param("limit", request.fetchSize())
                        .query(JdbcDisruptionReader::list)
                        .list());
        return Page.of(
                request,
                rows,
                row -> TimeIdCursors.of(row.episodeStart(), row.id()).keys());
    }

    @Override
    public Optional<DisruptionEpisode> find(UUID id) {
        return metrics.time(
                "reader",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id)
                        .query(JdbcDisruptionReader::detail)
                        .optional());
    }

    private static DisruptionEpisode list(ResultSet rs, int row) throws SQLException {
        return map(rs, false);
    }

    private static DisruptionEpisode detail(ResultSet rs, int row) throws SQLException {
        return map(rs, true);
    }

    private static DisruptionEpisode map(ResultSet rs, boolean detail) throws SQLException {
        return new DisruptionEpisode(
                ResultSets.uuid(rs, "id"),
                rs.getString("route_id"),
                rs.getInt("direction_id"),
                ResultSets.instant(rs, "episode_start"),
                ResultSets.nullableInstant(rs, "episode_end"),
                rs.getString("status"),
                rs.getInt("severity"),
                rs.getString("audience"),
                rs.getBigDecimal("baseline_mean_seconds"),
                rs.getBigDecimal("baseline_stddev_seconds"),
                rs.getBigDecimal("current_avg_delay_seconds"),
                rs.getBigDecimal("current_z_score"),
                rs.getBigDecimal("peak_avg_delay_seconds"),
                rs.getBigDecimal("peak_z_score"),
                rs.getInt("sample_count"),
                ResultSets.textArray(rs, "affected_stop_ids"),
                ResultSets.instant(rs, "last_bucket"),
                rs.getString("enrichment_status"),
                rs.getBigDecimal("data_issue_probability"),
                rs.getString("likely_cause"),
                rs.getBigDecimal("cause_confidence"),
                rs.getString("model_version"),
                detail ? rs.getString("close_reason") : null,
                detail ? ResultSets.nullableUuid(rs, "batch_id") : null,
                detail ? ResultSets.nullableInstant(rs, "enriched_at") : null);
    }
}
