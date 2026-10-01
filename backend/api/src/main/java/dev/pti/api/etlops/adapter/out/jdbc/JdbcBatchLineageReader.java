package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.BatchLineageReader;
import dev.pti.api.etlops.domain.BatchLineage;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traces a {@code batch_id} (DOC-32 E-37, FR-12.5): the micro-batch log first, then the steps, then the dead letters
 * and data quality results that carry the id. No fact table is counted (it can hold millions of rows): the numbers are
 * the counters of the batch or the step.
 */
@Component
public final class JdbcBatchLineageReader implements BatchLineageReader {

    private static final String STREAM = "etlops/batch_stream";
    private static final String STEP = "etlops/batch_step";
    private static final String DEAD_LETTERS = "etlops/batch_dead_letters";
    private static final String QUALITY = "etlops/batch_dq";
    private static final String STREAM_SQL = SqlResources.read(STREAM);
    private static final String STEP_SQL = SqlResources.read(STEP);
    private static final String DEAD_LETTERS_SQL = SqlResources.read(DEAD_LETTERS);
    private static final String QUALITY_SQL = SqlResources.read(QUALITY);

    private static final BatchLineage.DeadLetters NO_DEAD_LETTERS = new BatchLineage.DeadLetters(0, Map.of());

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows rows;

    public JdbcBatchLineageReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new EtlRows(mapper);
    }

    @Override
    public Optional<BatchLineage> find(UUID batchId) {
        Optional<BatchLineage> found = stream(batchId);
        if (found.isEmpty()) {
            found = step(batchId);
        }
        return found;
    }

    private Optional<BatchLineage> stream(UUID batchId) {
        return metrics.time(
                        "reader",
                        STREAM,
                        () -> jdbc.sql(STREAM_SQL)
                                .param("batchId", batchId.toString())
                                .query((rs, row) -> {
                                    String listener = rs.getString("listener_id");
                                    Instant started = ResultSets.instant(rs, "started_at");
                                    Instant minute = started.truncatedTo(ChronoUnit.MINUTES);
                                    return new BatchLineage(
                                            batchId,
                                            BatchLineage.Origin.STREAM,
                                            new RunId(RunKind.STREAM, null, listener, minute).text(),
                                            null,
                                            null,
                                            null,
                                            listener,
                                            rs.getString("source"),
                                            rs.getString("instance_id"),
                                            rows.object(rs.getString("offsets")),
                                            rs.getString("write_mode"),
                                            started,
                                            ResultSets.instant(rs, "finished_at"),
                                            new BatchLineage.Counts(
                                                    rs.getInt("records_read"),
                                                    rs.getInt("records_written"),
                                                    rs.getInt("records_skipped")),
                                            null,
                                            NO_DEAD_LETTERS,
                                            List.of());
                                })
                                .optional())
                .map(lineage -> withDetails(lineage));
    }

    private Optional<BatchLineage> step(UUID batchId) {
        return metrics.time(
                        "reader",
                        STEP,
                        () -> jdbc.sql(STEP_SQL)
                                .param("batchId", batchId.toString())
                                .query((rs, row) -> new BatchLineage(
                                        batchId,
                                        BatchLineage.Origin.BATCH_STEP,
                                        "job:" + rs.getLong("job_execution_id"),
                                        rs.getString("job_name"),
                                        rs.getString("step_name"),
                                        rs.getLong("step_execution_id"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        ResultSets.nullableInstant(rs, "started_at"),
                                        ResultSets.nullableInstant(rs, "ended_at"),
                                        new BatchLineage.Counts(
                                                rs.getLong("read_count"),
                                                rs.getLong("write_count"),
                                                rs.getLong("skip_count")),
                                        ResultSets.nullableUuid(rs, "replay_request_id"),
                                        NO_DEAD_LETTERS,
                                        List.of()))
                                .optional())
                .map(lineage -> withDetails(lineage));
    }

    /** Adds the dead letters and data quality results, which both origins have. */
    private BatchLineage withDetails(BatchLineage lineage) {
        String id = lineage.batchId().toString();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        metrics.time(
                        "reader",
                        DEAD_LETTERS,
                        () -> jdbc.sql(DEAD_LETTERS_SQL)
                                .param("batchId", id)
                                .query((rs, row) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                                .list())
                .forEach(entry -> byStatus.put(entry.getKey(), entry.getValue()));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        List<BatchLineage.DataQualityResult> quality = metrics.time(
                "reader",
                QUALITY,
                () -> jdbc.sql(QUALITY_SQL)
                        .param("batchId", id)
                        .query((rs, row) -> new BatchLineage.DataQualityResult(
                                rs.getString("rule_id"),
                                rs.getString("table_name"),
                                rs.getLong("violation_count"),
                                ResultSets.instant(rs, "checked_at")))
                        .list());
        return new BatchLineage(
                lineage.batchId(),
                lineage.origin(),
                lineage.runId(),
                lineage.jobName(),
                lineage.stepName(),
                lineage.stepExecutionId(),
                lineage.listenerId(),
                lineage.source(),
                lineage.instanceId(),
                lineage.offsets(),
                lineage.writeMode(),
                lineage.startedAt(),
                lineage.endedAt(),
                lineage.counts(),
                lineage.replayRequestId(),
                new BatchLineage.DeadLetters(total, byStatus),
                quality);
    }
}
