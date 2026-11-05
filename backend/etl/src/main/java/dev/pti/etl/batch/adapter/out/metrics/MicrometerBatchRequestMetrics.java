package dev.pti.etl.batch.adapter.out.metrics;

import dev.pti.etl.batch.application.port.BatchRequestMetrics;
import dev.pti.etl.batch.application.port.JobRequestStore.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@link BatchRequestMetrics} on Micrometer (DOC-19 §7.3). */
public final class MicrometerBatchRequestMetrics implements BatchRequestMetrics {

    private final MeterRegistry meters;

    public MicrometerBatchRequestMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void requestHandled(Kind kind, String outcome) {
        Counter.builder("pti.batch.job.requests")
                .tag("kind", kind.name())
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
