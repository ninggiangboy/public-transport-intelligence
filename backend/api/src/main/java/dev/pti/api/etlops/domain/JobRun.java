package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code ops.ops_job_run_v} (DOC-15 §5, DOC-32 E-30). A stream run has no exit code, no job execution and
 * no {@code restartable}; a batch job has no duplicate count. {@code batchIds} holds at most 20 ids, {@code
 * batchCount} all of them.
 */
public record JobRun(
        String runId,
        RunKind kind,
        String name,
        String status,
        @Nullable String exitCode,
        @Nullable String exitMessage,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        long readCount,
        long writeCount,
        long skipCount,
        @Nullable Long duplicateCount,
        @Nullable Long jobExecutionId,
        List<String> batchIds,
        int batchCount,
        @Nullable Boolean restartable) {

    public JobRun {
        batchIds = List.copyOf(batchIds);
    }

    public JobRun withRestartable(boolean value) {
        return new JobRun(
                runId,
                kind,
                name,
                status,
                exitCode,
                exitMessage,
                startedAt,
                endedAt,
                readCount,
                writeCount,
                skipCount,
                duplicateCount,
                jobExecutionId,
                batchIds,
                batchCount,
                value);
    }

    /**
     * Sets {@code restartable} the way DOC-32 E-30 defines it: a batch job that is {@code FAILED} or {@code STOPPED} and
     * whose job can be restarted (DOC-19 §2). A stream run has no such member.
     */
    public JobRun withRestartability() {
        if (!isBatchJob()) {
            return this;
        }
        boolean ended = status.equals("FAILED") || status.equals("STOPPED");
        return withRestartable(ended && JobCatalog.isRestartable(name));
    }

    public boolean isBatchJob() {
        return kind == RunKind.BATCH_JOB;
    }
}
