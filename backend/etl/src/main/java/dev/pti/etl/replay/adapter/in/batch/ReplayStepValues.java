package dev.pti.etl.replay.adapter.in.batch;

import dev.pti.etl.batch.domain.BatchId;
import dev.pti.etl.batch.domain.StepKeys;
import java.util.UUID;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;

/**
 * The step values a replay writer and listener need, read from the keys shared in {@code batch.domain} so that the
 * replay adapter does not depend on the batch adapter.
 */
final class ReplayStepValues {

    private ReplayStepValues() {}

    /** The step execution of the calling thread; only set while Spring Batch runs a step. */
    static StepExecution current() {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No step is running on this thread");
        }
        return context.getStepExecution();
    }

    static UUID batchId(StepExecution step) {
        return BatchId.of(step.getExecutionContext().getString(StepKeys.BATCH_ID, ""), step.getStepName());
    }

    static long rejected(StepExecution step) {
        return step.getExecutionContext().getLong(StepKeys.REJECTED, 0L);
    }

    static void addRejected(StepExecution step, int count) {
        if (count > 0) {
            step.getExecutionContext().putLong(StepKeys.REJECTED, rejected(step) + count);
        }
    }
}
