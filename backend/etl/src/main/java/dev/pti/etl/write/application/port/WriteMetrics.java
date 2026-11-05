package dev.pti.etl.write.application.port;

import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.write.domain.RunMode;
import dev.pti.etl.write.domain.WriteOutcome;

/**
 * {@code pti_etl_records_total} and {@code pti_etl_duplicates_total} (DOC-28, DOC-19 §10). Counted after commit, so
 * a rolled-back and retried chunk is counted once.
 */
public interface WriteMetrics {

    void recordWrite(EtlSource source, RunMode mode, WriteOutcome outcome);

    /** Messages skipped before the writer: read and process errors. */
    void recordSkipped(EtlSource source, RunMode mode, int count);
}
