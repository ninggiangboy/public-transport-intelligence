package dev.pti.etl.maintenance.application.port;

import java.time.LocalDateTime;

/** Deletes Spring Batch metadata older than a cutoff, a batch of job instances per transaction (DR-62, DOC-18 §5). */
public interface BatchMetadataStore {

    /** Deletes at most {@code limit} expired job instances, children first; returns how many it deleted. */
    int deleteBatch(LocalDateTime cutoff, int limit);
}
