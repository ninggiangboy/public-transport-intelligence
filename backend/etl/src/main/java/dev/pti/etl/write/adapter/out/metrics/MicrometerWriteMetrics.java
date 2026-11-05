package dev.pti.etl.write.adapter.out.metrics;

import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.write.adapter.out.jdbc.AfterCommit;
import dev.pti.etl.write.application.port.WriteMetrics;
import dev.pti.etl.write.domain.RunMode;
import dev.pti.etl.write.domain.WriteOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@link WriteMetrics} on Micrometer (DOC-28): the write and duplicate counters, incremented after commit. */
public final class MicrometerWriteMetrics implements WriteMetrics {

    private final MeterRegistry meters;

    public MicrometerWriteMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
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

    @Override
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
