package dev.pti.etl.batch.adapter.out.jdbc;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.adapter.in.batch.JobParams;
import dev.pti.etl.batch.application.port.JobRequestStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** {@link JobRequestStore} on {@code ops.job_request} (DOC-19 §7.3); every update only moves a request forward. */
public class JobRequests implements JobRequestStore {

    /** Longest message stored on a request; the full exit description stays in Spring Batch metadata. */
    public static final int MAX_MESSAGE = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JobRequests(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Claims the oldest {@code PENDING} request; {@code SKIP LOCKED} keeps two pods from claiming the same one. */
    @Override
    public Optional<JobRequest> claim() {
        return Optional.ofNullable(tx.execute(status -> {
            JobRequest request = jdbc.query(
                    """
                    SELECT id, kind, job_name, job_parameters::text AS parameters, target_job_execution_id
                    FROM ops.job_request
                    WHERE status = 'PENDING'
                    ORDER BY requested_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """,
                    rs -> rs.next()
                            ? new JobRequest(
                                    rs.getObject("id", UUID.class),
                                    Kind.valueOf(rs.getString("kind")),
                                    rs.getString("job_name"),
                                    parameters(rs.getString("parameters")),
                                    rs.getObject("target_job_execution_id", Long.class))
                            : null);
            if (request != null) {
                jdbc.update(
                        "UPDATE ops.job_request SET status = 'RUNNING', started_at = now() WHERE id = ?", request.id());
            }
            return request;
        }));
    }

    /** Puts a claimed request back, e.g. when the job executor queue is full (DOC-19 §3.3). */
    @Override
    public void release(UUID id) {
        jdbc.update(
                "UPDATE ops.job_request SET status = 'PENDING', started_at = NULL WHERE id = ? AND status = 'RUNNING'",
                id);
    }

    @Override
    public void started(UUID id, long jobExecutionId) {
        jdbc.update(
                "UPDATE ops.job_request SET job_execution_id = ? WHERE id = ? AND status = 'RUNNING'",
                jobExecutionId,
                id);
    }

    @Override
    public void reject(UUID id, String message) {
        end(id, "REJECTED", message);
    }

    /** A {@code STOP} request is done once the stop has been signalled. */
    @Override
    public void done(UUID id, long jobExecutionId, String message) {
        jdbc.update("""
                UPDATE ops.job_request SET status = 'DONE', job_execution_id = ?, finished_at = now(), message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, jobExecutionId, truncate(message), id);
    }

    /** Records the end of a Spring Batch execution on its request (e.g. from {@code StaleExecutionRecoverer}). */
    public int finish(JobExecution execution) {
        String description = execution.getExitStatus().getExitDescription();
        return finish(new FinishedJob(
                execution.getStatus() == BatchStatus.COMPLETED,
                execution.getExitStatus().getExitCode(),
                description,
                execution.getId(),
                execution.getJobParameters().getString(JobParams.JOB_REQUEST_ID)));
    }

    /**
     * Records the end of an execution on the request that started it: by execution id, or by the
     * {@code jobRequestId} parameter when the job ended before the poller linked it.
     */
    @Override
    public int finish(FinishedJob finished) {
        String message = finished.exitCode()
                + (finished.exitDescription() == null
                                || finished.exitDescription().isBlank()
                        ? ""
                        : ": " + finished.exitDescription());
        String requestId = finished.requestId();
        return jdbc.update(
                """
                UPDATE ops.job_request
                SET status = ?, job_execution_id = ?, finished_at = now(), message = ?
                WHERE status = 'RUNNING' AND kind <> 'STOP' AND (job_execution_id = ? OR id::text = ?)
                """,
                finished.completed() ? "DONE" : "FAILED",
                finished.jobExecutionId(),
                truncate(message),
                finished.jobExecutionId(),
                requestId == null ? "" : requestId);
    }

    /** Requests claimed by a pod that died before it started the job (DOC-19 §7.3). */
    @Override
    public int failInterrupted(Duration olderThan) {
        return jdbc.update("""
                UPDATE ops.job_request
                SET status = 'FAILED', finished_at = now(), message = 'The launch was interrupted; submit it again'
                WHERE status = 'RUNNING' AND job_execution_id IS NULL
                  AND started_at < now() - make_interval(secs => ?)
                """, (double) olderThan.toSeconds());
    }

    private void end(UUID id, String status, String message) {
        jdbc.update("""
                UPDATE ops.job_request SET status = ?, finished_at = now(), message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, status, truncate(message), id);
    }

    public static String truncate(String message) {
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 1) + "…";
    }

    /** {@code job_parameters} is a flat JSON object; values are passed on as text. */
    public static Map<String, String> parameters(@Nullable String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode node = MessageJson.mapper().readTree(json);
        node.properties()
                .forEach(e -> result.put(
                        e.getKey(),
                        e.getValue().isString()
                                ? e.getValue().stringValue()
                                : e.getValue().toString()));
        return result;
    }
}
