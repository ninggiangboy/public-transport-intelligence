package dev.pti.etl.maintenance.adapter.in.batch;

import dev.pti.etl.maintenance.application.CleanupBatchMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Deletes Spring Batch metadata older than the retention (DR-62, DOC-18 §5), a batch of job instances per
 * transaction. A {@code FAILED} execution that nobody restarted stays for diagnosis (test L-04).
 */
public class BatchMetadataCleanupTasklet implements Tasklet {

    static final String DELETED_KEY = "pti.purge.deleted.batch_job_instance";

    private final CleanupBatchMetadata cleanup;
    private final Clock batchClock;
    private final Duration retention;
    private final int batchSize;

    /** @param batchClock the clock of Spring Batch's {@code LocalDateTime} columns (the JVM zone, UTC) */
    public BatchMetadataCleanupTasklet(
            CleanupBatchMetadata cleanup, Clock batchClock, Duration retention, int batchSize) {
        this.cleanup = cleanup;
        this.batchClock = batchClock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        LocalDateTime cutoff = LocalDateTime.now(batchClock).minus(retention);
        int deleted = cleanup.cleanupOne(cutoff, batchSize);
        if (deleted > 0) {
            context.putLong(DELETED_KEY, context.getLong(DELETED_KEY, 0L) + deleted);
            contribution.incrementWriteCount(deleted);
        }
        if (deleted < batchSize) {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                    "Deleted " + context.getLong(DELETED_KEY, 0L) + " job instance(s) older than " + cutoff));
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }
}
