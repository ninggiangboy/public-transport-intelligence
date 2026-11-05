package dev.pti.etl.stream.adapter.out.metrics;

import dev.pti.common.error.ErrorKind;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.stream.application.StreamChunkResult;
import dev.pti.etl.stream.application.port.StreamMetrics;
import dev.pti.etl.write.domain.RunMode;
import dev.pti.etl.write.domain.WriteMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/** {@link StreamMetrics} on Micrometer (DOC-28): the same meter names and tags the template used directly before. */
public final class MicrometerStreamMetrics implements StreamMetrics {

    private final MeterRegistry meters;

    public MicrometerStreamMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void batchCommitted(EtlSource source, StreamChunkResult.BatchStatus status) {
        Counter.builder("pti.etl.stream.batches")
                .tag("source", source.name())
                .tag("status", status.name())
                .register(meters)
                .increment();
    }

    @Override
    public void scanRequested(EtlSource source) {
        Counter.builder("pti.etl.chunk.scan")
                .tag("source", source.name())
                .tag("mode", RunMode.STREAM.tag())
                .register(meters)
                .increment();
    }

    @Override
    public void chunkDuration(EtlSource source, WriteMode mode, Duration duration) {
        Timer.builder("pti.etl.chunk.duration")
                .tag("source", source.name())
                .tag("mode", RunMode.STREAM.tag())
                .tag("write_mode", mode.name().toLowerCase(Locale.ROOT))
                .register(meters)
                .record(duration);
    }

    @Override
    public void commitLatency(EtlSource source, Duration duration) {
        Timer.builder("pti.etl.kafka.to.commit")
                .tag("source", source.name())
                .register(meters)
                .record(duration);
    }

    @Override
    public void error(ErrorKind kind, Throwable error) {
        ErrorMetrics.record(meters, kind, error);
    }
}
