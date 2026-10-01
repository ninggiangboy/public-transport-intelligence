package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ops.job_request}: a manual run, restart or stop of a batch job that {@code etl-batch} executes
 * (ADR-0013, DOC-32 E-33…E-36). {@code parameters} are in the text form the poller understands.
 */
public record JobRequest(
        UUID id,
        JobRequestKind kind,
        String jobName,
        Map<String, String> parameters,
        @Nullable Long targetJobExecutionId,
        String requestedBy,
        @Nullable String idempotencyKey,
        Instant requestedAt,
        String status,
        @Nullable Long jobExecutionId,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        @Nullable String message) {

    public JobRequest {
        parameters = Map.copyOf(parameters);
    }

    /** A request as it is submitted: {@code PENDING}, nothing executed yet. */
    public static JobRequest pending(
            UUID id,
            JobRequestKind kind,
            String jobName,
            Map<String, String> parameters,
            @Nullable Long targetJobExecutionId,
            String requestedBy,
            @Nullable String idempotencyKey,
            Instant requestedAt) {
        return new JobRequest(
                id,
                kind,
                jobName,
                parameters,
                targetJobExecutionId,
                requestedBy,
                idempotencyKey,
                requestedAt,
                "PENDING",
                null,
                null,
                null,
                null);
    }

    /** True when the other request asks for the same thing: what an {@code Idempotency-Key} protects (DOC-31 §8). */
    public boolean sameAsk(JobRequest other) {
        return kind == other.kind
                && jobName.equals(other.jobName)
                && parameters.equals(other.parameters)
                && Objects.equals(targetJobExecutionId, other.targetJobExecutionId);
    }

    /** {@code job:<jobExecutionId>} once the request has an execution; for a stop, the one it stops. */
    public @Nullable String runId() {
        return jobExecutionId != null ? "job:" + jobExecutionId : null;
    }

    public @Nullable String targetRunId() {
        return targetJobExecutionId != null ? "job:" + targetJobExecutionId : null;
    }
}
