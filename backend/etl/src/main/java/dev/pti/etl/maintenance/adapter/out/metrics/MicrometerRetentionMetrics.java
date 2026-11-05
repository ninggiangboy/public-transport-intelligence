package dev.pti.etl.maintenance.adapter.out.metrics;

import dev.pti.etl.maintenance.application.port.RetentionMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@code pti_retention_deleted_total{table}}: rows, or partitions for the fact tables (DOC-18 §5). */
public final class MicrometerRetentionMetrics implements RetentionMetrics {

    private final MeterRegistry meters;

    public MicrometerRetentionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void deleted(String table, long count) {
        if (count > 0) {
            Counter.builder("pti.retention.deleted")
                    .tag("table", table)
                    .register(meters)
                    .increment(count);
        }
    }
}
