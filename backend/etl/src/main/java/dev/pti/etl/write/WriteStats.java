package dev.pti.etl.write;

import dev.pti.etl.core.EtlSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code pti_etl_records_total} and {@code pti_etl_duplicates_total} (DOC-28, DOC-19 §10). Counted after commit, so
 * a rolled-back and retried chunk is counted once.
 */
public final class WriteStats {

    private final MeterRegistry meters;

    public WriteStats(MeterRegistry meters) {
        this.meters = meters;
    }

    public void recordWrite(EtlSource source, RunMode mode, WriteOutcome outcome) {
        Counter written = records(source, mode, "written");
        Counter duplicate = records(source, mode, "duplicate");
        Counter skipped = records(source, mode, "skipped");
        Counter inChunk = duplicates(source, mode, "in_chunk");
        Counter registry = duplicates(source, mode, "registry");
        Counter guard = duplicates(source, mode, "guard");
        AfterCommit.run(() -> {
            written.increment(outcome.writtenCount());
            duplicate.increment(outcome.duplicate());
            skipped.increment(outcome.rejected());
            inChunk.increment(outcome.duplicateInChunk());
            registry.increment(outcome.duplicateRegistry());
            guard.increment(outcome.duplicateGuard());
        });
    }

    /** Messages skipped before the writer: read and process errors. */
    public void recordSkipped(EtlSource source, RunMode mode, int count) {
        if (count > 0) {
            Counter skipped = records(source, mode, "skipped");
            AfterCommit.run(() -> skipped.increment(count));
        }
    }

    private Counter records(EtlSource source, RunMode mode, String outcome) {
        return Counter.builder("pti.etl.records")
                .tag("source", source.name())
                .tag("mode", mode.tag())
                .tag("outcome", outcome)
                .register(meters);
    }

    private Counter duplicates(EtlSource source, RunMode mode, String reason) {
        return Counter.builder("pti.etl.duplicates")
                .tag("source", source.name())
                .tag("mode", mode.tag())
                .tag("reason", reason)
                .register(meters);
    }
}
