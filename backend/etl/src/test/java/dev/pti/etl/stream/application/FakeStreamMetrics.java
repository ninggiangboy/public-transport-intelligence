package dev.pti.etl.stream.application;

import dev.pti.common.error.ErrorKind;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.stream.application.port.StreamMetrics;
import dev.pti.etl.write.domain.WriteMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A {@link StreamMetrics} that records the error kinds and ignores the meters. */
final class FakeStreamMetrics implements StreamMetrics {

    final List<ErrorKind> errors = new ArrayList<>();

    @Override
    public void batchCommitted(EtlSource source, StreamChunkResult.BatchStatus status) {}

    @Override
    public void scanRequested(EtlSource source) {}

    @Override
    public void chunkDuration(EtlSource source, WriteMode mode, Duration duration) {}

    @Override
    public void commitLatency(EtlSource source, Duration duration) {}

    @Override
    public void error(ErrorKind kind, Throwable error) {
        errors.add(kind);
    }
}
