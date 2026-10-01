package dev.pti.analytics.recompute.adapter.out.metrics;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.port.RecomputeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link RecomputeMetrics} on Micrometer: {@code pti.analytics.recompute.rows} is
 * {@code pti_analytics_recompute_rows_total} in Prometheus (DOC-23 §14.1).
 */
public class MicrometerRecomputeMetrics implements RecomputeMetrics {

    private final MeterRegistry registry;

    public MicrometerRecomputeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void upserted(Detector detector, long rows) {
        count(detector, "upserted", rows);
    }

    @Override
    public void deleted(Detector detector, long rows) {
        count(detector, "deleted", rows);
    }

    private void count(Detector detector, String op, long rows) {
        Counter.builder("pti.analytics.recompute.rows")
                .tag("detector", detector.tag())
                .tag("op", op)
                .register(registry)
                .increment(rows);
    }
}
