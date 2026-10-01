package dev.pti.api.etlops.domain;

/** What one entry of the run list is (DOC-15 §5): a Spring Batch job execution or a minute of streaming micro-batches. */
public enum RunKind {
    BATCH_JOB,
    STREAM
}
