package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.domain.ReplayDetail;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.etlops.domain.ReplayFilter;
import dev.pti.api.etlops.domain.ReplayProgress;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The replays and the micro-batch log of an estimate (DOC-32 E-51…E-53), read as {@code api_reader}. */
@Component
public final class JdbcReplayReader implements ReplayReader {

    private static final String LIST = "etlops/replays_list";
    private static final String GET = "etlops/replay_get";
    private static final String PROGRESS = "etlops/replay_progress";
    private static final String HISTORY = "etlops/replay_history";
    private static final String ACTIVE_RAW = "etlops/replay_active_raw";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String PROGRESS_SQL = SqlResources.read(PROGRESS);
    private static final String HISTORY_SQL = SqlResources.read(HISTORY);
    private static final String ACTIVE_RAW_SQL = SqlResources.read(ACTIVE_RAW);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final ReplayRows rows;

    public JdbcReplayReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new ReplayRows(new EtlRows(mapper));
    }

    @Override
    public Page<ReplayRequest> list(ReplayFilter filter, PageRequest page) {
        TimeIdCursors.TimeId after = TimeIdCursors.parse(page.after());
        List<ReplayRequest> found = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.nullableTimestamp(filter.from()))
                        .param("to", ResultSets.nullableTimestamp(filter.to()))
                        .param("kind", Objects.toString(filter.kind(), null))
                        .param("statuses", filter.statuses().toArray(String[]::new))
                        .param("source", filter.source())
                        .param("requestedBy", filter.requestedBy())
                        .param("cursorTs", after != null ? ResultSets.timestamp(after.time()) : null)
                        .param("cursorId", after != null ? after.id().toString() : null)
                        .param("limit", page.fetchSize())
                        .query(rows::map)
                        .list());
        return Page.of(
                page,
                found,
                replay -> TimeIdCursors.of(replay.requestedAt(), replay.id()).keys());
    }

    @Override
    public Optional<ReplayDetail> find(UUID id) {
        Optional<ReplayRequest> request = metrics.time(
                "reader",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id.toString())
                        .query(rows::map)
                        .optional());
        return request.map(replay -> new ReplayDetail(replay, progress(replay)));
    }

    private ReplayProgress progress(ReplayRequest replay) {
        if (!replay.status().equals("RUNNING") || replay.jobExecutionId() == null) {
            return null;
        }
        return metrics.time(
                        "reader",
                        PROGRESS,
                        () -> jdbc.sql(PROGRESS_SQL)
                                .param("jobExecutionId", replay.jobExecutionId())
                                .query((rs, row) -> new ReplayProgress(
                                        rs.getString("step_name"),
                                        rs.getLong("read_count"),
                                        rs.getLong("write_count"),
                                        rs.getLong("skip_count"),
                                        ResultSets.nullableInstant(rs, "read_at")))
                                .optional())
                .orElse(null);
    }

    @Override
    public ReplayEstimate.History history(String source, Instant from, Instant to) {
        return metrics.time(
                "reader",
                HISTORY,
                () -> jdbc.sql(HISTORY_SQL)
                        .param("source", source)
                        .param("from", ResultSets.nullableTimestamp(from))
                        .param("to", ResultSets.nullableTimestamp(to))
                        .param("toPlusLookahead", ResultSets.nullableTimestamp(to.plus(ReplayEstimate.LOOKAHEAD)))
                        .query((rs, row) -> new ReplayEstimate.History(
                                rs.getLong("records_read"), rs.getLong("batches"), rs.getLong("minutes_with_batches")))
                        .single());
    }

    @Override
    public boolean rawReplayActive(String source) {
        return metrics.time(
                        "reader",
                        ACTIVE_RAW,
                        () -> jdbc.sql(ACTIVE_RAW_SQL)
                                .param("source", source)
                                .query((rs, row) -> rs.getObject("id", UUID.class))
                                .optional())
                .isPresent();
    }
}
