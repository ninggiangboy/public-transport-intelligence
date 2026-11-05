package dev.pti.etl.batch.domain;

import java.util.UUID;

/**
 * The {@code batch_id} every step execution carries (DR-63, DOC-19 §4.6). {@code BatchIdStepListener} stamps it on the
 * step's execution context before the step runs, and every writer reads it from there and puts it on the rows it
 * writes. It is a plain value of the domain, so batch jobs of other features read the same key.
 */
public final class BatchId {

    /** Step context key of the step's batch_id. */
    public static final String CONTEXT_KEY = "pti.batchId";

    private BatchId() {}

    /** Reads the batch_id of a step's context value; a step without one is a wiring bug. */
    public static UUID of(String value, String stepName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "Step " + stepName + " has no " + CONTEXT_KEY + "; is BatchIdStepListener registered?");
        }
        return UUID.fromString(value);
    }
}
