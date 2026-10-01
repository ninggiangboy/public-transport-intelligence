package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobParameter;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunFilter;
import dev.pti.api.etlops.domain.JobSummary;
import dev.pti.api.etlops.domain.RequestRef;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.etlops.domain.StepRun;
import dev.pti.api.etlops.domain.StreamBatch;
import dev.pti.api.etlops.domain.SummaryBucket;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runs of {@code ops.ops_job_run_v} and what hangs off them (DOC-32 E-30…E-32), read as {@code api_reader}, who
 * reads the views and never {@code batch.*} (DR-62).
 */
@Component
public final class JdbcJobRunReader implements JobRunReader {

    private static final String LIST = "etlops/job_runs";
    private static final String BATCH = "etlops/job_run_batch";
    private static final String STREAM = "etlops/job_run_stream";
    private static final String PARAMETERS = "etlops/job_run_parameters";
    private static final String STEPS = "etlops/job_run_steps";
    private static final String REQUEST = "etlops/job_run_request";
    private static final String STREAM_BATCHES = "etlops/job_run_stream_batches";
    private static final String SUMMARY_STREAM = "etlops/job_summary_stream";
    private static final String SUMMARY_JOBS = "etlops/job_summary_jobs";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String BATCH_SQL = SqlResources.read(BATCH);
    private static final String STREAM_SQL = SqlResources.read(STREAM);
    private static final String PARAMETERS_SQL = SqlResources.read(PARAMETERS);
    private static final String STEPS_SQL = SqlResources.read(STEPS);
    private static final String REQUEST_SQL = SqlResources.read(REQUEST);
    private static final String STREAM_BATCHES_SQL = SqlResources.read(STREAM_BATCHES);
    private static final String SUMMARY_STREAM_SQL = SqlResources.read(SUMMARY_STREAM);
    private static final String SUMMARY_JOBS_SQL = SqlResources.read(SUMMARY_JOBS);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows rows;

    public JdbcJobRunReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new EtlRows(mapper);
    }

    @Override
    public Page<JobRun> list(JobRunFilter filter, PageRequest page) {
        KeysetCursor after = page.after();
        Instant cursorTs = after == null ? null : EtlRows.instantKey(after, 0);
        String cursorRunId = after == null ? null : EtlRows.textKey(after, 1);
        List<JobRun> found = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("from", ResultSets.nullableTimestamp(filter.from()))
                        .param("to", ResultSets.nullableTimestamp(filter.to()))
                        .param("kind", Objects.toString(filter.kind(), null))
                        .param("names", filter.names().toArray(String[]::new))
                        .param("statuses", filter.statuses().toArray(String[]::new))
                        .param("cursorTs", ResultSets.nullableTimestamp(cursorTs))
                        .param("cursorRunId", cursorRunId)
                        .param("limit", page.fetchSize())
                        .query(JdbcJobRunReader::run)
                        .list());
        return Page.of(page, found, run -> EtlRows.keys(Objects.requireNonNull(run.startedAt()), run.runId()));
    }

    @Override
    public Optional<JobRun> find(RunId id) {
        if (id.kind() == RunKind.BATCH_JOB) {
            return metrics.time(
                    "reader",
                    BATCH,
                    () -> jdbc.sql(BATCH_SQL)
                            .param("jobExecutionId", id.jobExecutionId())
                            .query(JdbcJobRunReader::run)
                            .optional());
        }
        return metrics.time(
                "reader",
                STREAM,
                () -> jdbc.sql(STREAM_SQL)
                        .param("listenerId", id.listenerId())
                        .param("minute", ResultSets.nullableTimestamp(id.minute()))
                        .query(JdbcJobRunReader::run)
                        .optional());
    }

    @Override
    public List<JobParameter> parameters(long jobExecutionId) {
        return metrics.time(
                "reader",
                PARAMETERS,
                () -> jdbc.sql(PARAMETERS_SQL)
                        .param("jobExecutionId", jobExecutionId)
                        .query((rs, row) -> new JobParameter(
                                rs.getString("name"),
                                rs.getString("type"),
                                rs.getString("value"),
                                rs.getBoolean("identifying")))
                        .list());
    }

    @Override
    public List<StepRun> steps(long jobExecutionId) {
        return metrics.time(
                "reader",
                STEPS,
                () -> jdbc.sql(STEPS_SQL)
                        .param("jobExecutionId", jobExecutionId)
                        .query((rs, row) -> new StepRun(
                                rs.getLong("step_execution_id"),
                                rs.getString("step_name"),
                                rs.getString("status"),
                                rs.getString("exit_code"),
                                rs.getString("exit_message"),
                                ResultSets.nullableInstant(rs, "started_at"),
                                ResultSets.nullableInstant(rs, "ended_at"),
                                rs.getLong("read_count"),
                                rs.getLong("write_count"),
                                rs.getLong("filter_count"),
                                rs.getLong("read_skip_count"),
                                rs.getLong("process_skip_count"),
                                rs.getLong("write_skip_count"),
                                rs.getLong("commit_count"),
                                rs.getLong("rollback_count"),
                                rs.getString("batch_id"),
                                rs.getString("short_context")))
                        .list());
    }

    @Override
    public Optional<RequestRef> requestOf(long jobExecutionId) {
        return metrics.time(
                "reader",
                REQUEST,
                () -> jdbc.sql(REQUEST_SQL)
                        .param("jobExecutionId", jobExecutionId)
                        .query((rs, row) -> new RequestRef(rs.getString("type"), rs.getObject("id", UUID.class)))
                        .optional());
    }

    @Override
    public List<StreamBatch> streamBatches(String listenerId, Instant minute, int limit) {
        return metrics.time(
                "reader",
                STREAM_BATCHES,
                () -> jdbc.sql(STREAM_BATCHES_SQL)
                        .param("listenerId", listenerId)
                        .param("minute", ResultSets.nullableTimestamp(minute))
                        .param("limit", limit)
                        .query((rs, row) -> new StreamBatch(
                                rs.getString("batch_id"),
                                rs.getString("status"),
                                rs.getString("write_mode"),
                                rs.getString("instance_id"),
                                rows.object(rs.getString("offsets")),
                                rs.getInt("records_read"),
                                rs.getInt("records_written"),
                                rs.getInt("records_skipped"),
                                rs.getInt("records_duplicate"),
                                ResultSets.nullableInstant(rs, "min_event_ts"),
                                ResultSets.nullableInstant(rs, "max_event_ts"),
                                ResultSets.instant(rs, "started_at"),
                                ResultSets.instant(rs, "finished_at"),
                                rs.getString("error_class"),
                                rs.getString("error_message")))
                        .list());
    }

    @Override
    public List<JobSummary.Row> streamSummary(Instant from, Instant to, SummaryBucket bucket) {
        return metrics.time(
                "reader",
                SUMMARY_STREAM,
                () -> jdbc.sql(SUMMARY_STREAM_SQL)
                        .param("bucketSeconds", (double) bucket.length().toSeconds())
                        .param("from", ResultSets.nullableTimestamp(from))
                        .param("to", ResultSets.nullableTimestamp(to))
                        .query((rs, row) -> new JobSummary.Row(
                                rs.getString("source"),
                                new JobSummary.Point(
                                        ResultSets.instant(rs, "bucket_start"),
                                        rs.getInt("batches"),
                                        rs.getInt("failed_batches"),
                                        rs.getLong("read_count"),
                                        rs.getLong("write_count"),
                                        rs.getLong("skip_count"),
                                        rs.getLong("duplicate_count"),
                                        rs.getInt("p95_batch_ms"))))
                        .list());
    }

    @Override
    public Map<String, Integer> batchJobStatuses(Instant from, Instant to) {
        List<Map.Entry<String, Integer>> counts = metrics.time(
                "reader",
                SUMMARY_JOBS,
                () -> jdbc.sql(SUMMARY_JOBS_SQL)
                        .param("from", ResultSets.nullableTimestamp(from))
                        .param("to", ResultSets.nullableTimestamp(to))
                        .query((rs, row) -> Map.entry(rs.getString("status"), rs.getInt("n")))
                        .list());
        Map<String, Integer> statuses = new HashMap<>();
        counts.forEach(entry -> statuses.put(entry.getKey(), entry.getValue()));
        return statuses;
    }

    private static JobRun run(ResultSet rs, int row) throws SQLException {
        return new JobRun(
                rs.getString("run_id"),
                RunKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("exit_code"),
                rs.getString("exit_message"),
                ResultSets.nullableInstant(rs, "started_at"),
                ResultSets.nullableInstant(rs, "ended_at"),
                rs.getLong("read_count"),
                rs.getLong("write_count"),
                rs.getLong("skip_count"),
                EtlRows.longValue(rs, "duplicate_count"),
                EtlRows.longValue(rs, "job_execution_id"),
                EtlRows.uuidList(rs, "batch_ids"),
                rs.getInt("batch_count"),
                null);
    }
}
