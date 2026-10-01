package dev.pti.api.etlops.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A step execution of a batch job, from {@code ops.ops_job_step_v} (DOC-32 E-32). */
public record StepRun(
        long stepExecutionId,
        String stepName,
        String status,
        @Nullable String exitCode,
        @Nullable String exitMessage,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        long readCount,
        long writeCount,
        long filterCount,
        long readSkipCount,
        long processSkipCount,
        long writeSkipCount,
        long commitCount,
        long rollbackCount,
        @Nullable String batchId,
        @Nullable String executionContext) {}
