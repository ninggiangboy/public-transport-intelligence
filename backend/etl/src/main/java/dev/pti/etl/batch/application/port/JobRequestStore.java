package dev.pti.etl.batch.application.port;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code ops.job_request} (DOC-19 §7.3); every update only moves a request forward. The JDBC implementation lives in
 * {@code batch.adapter.out.jdbc}.
 */
public interface JobRequestStore {

    /** Claims the oldest PENDING request; {@code SKIP LOCKED} keeps two pods from claiming the same one. */
    Optional<JobRequest> claim();

    /** Puts a claimed request back, e.g. when the job executor queue is full (DOC-19 §3.3). */
    void release(UUID id);

    void started(UUID id, long jobExecutionId);

    /** Ends a request that cannot run, with its reason. */
    void reject(UUID id, String message);

    /** A {@code STOP} request is done once the stop has been signalled. */
    void done(UUID id, long jobExecutionId, String message);

    /** Fails the requests claimed by a pod that died before it started the job (DOC-19 §7.3). */
    int failInterrupted(Duration olderThan);

    /** Records the end of an execution on the request that started it. */
    int finish(FinishedJob finished);

    /** A request claimed by this pod: already RUNNING, not yet linked to an execution. */
    record JobRequest(
            UUID id,
            Kind kind,
            String jobName,
            Map<String, String> parameters,
            @Nullable Long targetExecutionId) {}

    /** A job execution that ended; the adapter maps Spring Batch's execution to this. */
    record FinishedJob(
            boolean completed,
            String exitCode,
            @Nullable String exitDescription,
            long jobExecutionId,
            @Nullable String requestId) {}

    enum Kind {
        RUN,
        RESTART,
        STOP
    }
}
