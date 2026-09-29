package dev.pti.etl.batch;

import java.util.UUID;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;

/** Values of the running step that the step-agnostic writer and listeners need (DOC-19 §3.2). */
public final class StepValues {

    /** Step context key of the step's {@code batch_id} (DR-63), set by {@link BatchIdStepListener}. */
    public static final String BATCH_ID = "pti.batchId";

    /** Step context key: items the chunk rules sent to the dead-letter queue, which Spring Batch does not count. */
    public static final String REJECTED = "pti.rejected";

    /** Step context counters of dead letters written by a replay: new rows and refreshed ones (DOC-22 §4.6). */
    public static final String DLQ_INSERTED = "pti.dlq.inserted";

    public static final String DLQ_UPDATED = "pti.dlq.updated";

    /** Job parameter that turns on replay semantics (DR-16, DOC-22). */
    public static final String REPLAY = "replay";

    private StepValues() {}

    /** The step execution of the calling thread; only set while Spring Batch runs a step. */
    public static StepExecution current() {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No step is running on this thread");
        }
        return context.getStepExecution();
    }

    public static UUID batchId(StepExecution step) {
        String value = step.getExecutionContext().getString(BATCH_ID, "");
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "Step " + step.getStepName() + " has no " + BATCH_ID + "; is BatchIdStepListener registered?");
        }
        return UUID.fromString(value);
    }

    public static boolean replay(StepExecution step) {
        String value = step.getJobParameters().getString(REPLAY);
        return Boolean.parseBoolean(value);
    }

    public static long rejected(StepExecution step) {
        return step.getExecutionContext().getLong(REJECTED, 0L);
    }

    public static void increment(StepExecution step, String key) {
        step.getExecutionContext().putLong(key, step.getExecutionContext().getLong(key, 0L) + 1);
    }

    public static void addRejected(StepExecution step, int count) {
        if (count > 0) {
            step.getExecutionContext().putLong(REJECTED, rejected(step) + count);
        }
    }
}
