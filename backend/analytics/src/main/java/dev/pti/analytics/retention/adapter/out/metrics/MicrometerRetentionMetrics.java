package dev.pti.analytics.retention.adapter.out.metrics;

import dev.pti.analytics.retention.application.port.RetentionMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link RetentionMetrics} on Micrometer: the same {@code pti.retention.deleted} counter, labelled by table, that the
 * other retention steps of {@code etl-batch} feed ({@code pti_retention_deleted_total} in Prometheus).
 */
public class MicrometerRetentionMetrics implements RetentionMetrics {

    private final MeterRegistry registry;

    public MicrometerRetentionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void deleted(String table, int count) {
        if (count > 0) {
            Counter.builder("pti.retention.deleted")
                    .tag("table", table)
                    .register(registry)
                    .increment(count);
        }
    }
}
