package dev.pti.etl.batch.domain;

/**
 * Step execution context keys of DOC-19 §3.2 shared by the batch jobs of every feature. They live in the domain so
 * that the job adapters of other features (gtfs, replay) read the same keys without depending on the batch adapter.
 */
public final class StepKeys {

    /** Step context key of the step's {@code batch_id} (DR-63), set by the batch id listener. */
    public static final String BATCH_ID = BatchId.CONTEXT_KEY;

    /** Step context key: items the chunk rules sent to the dead-letter queue, which Spring Batch does not count. */
    public static final String REJECTED = "pti.rejected";

    /** Step context counters of dead letters written by a replay: new rows and refreshed ones (DOC-22 §4.6). */
    public static final String DLQ_INSERTED = "pti.dlq.inserted";

    public static final String DLQ_UPDATED = "pti.dlq.updated";

    /** Job parameter that turns on replay semantics (DR-16, DOC-22). */
    public static final String REPLAY = "replay";

    private StepKeys() {}
}
