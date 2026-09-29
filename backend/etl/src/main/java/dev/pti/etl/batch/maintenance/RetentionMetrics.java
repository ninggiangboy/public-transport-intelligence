package dev.pti.etl.batch.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@code pti_retention_deleted_total{table}}: rows, or partitions for the fact tables (DOC-18 §5). */
final class RetentionMetrics {

    private final MeterRegistry meters;

    RetentionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void deleted(String table, long count) {
        if (count > 0) {
            Counter.builder("pti.retention.deleted")
                    .tag("table", table)
                    .register(meters)
                    .increment(count);
        }
    }
}
