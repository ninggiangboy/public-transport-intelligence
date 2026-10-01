package dev.pti.analytics.recompute.application.port;

import dev.pti.analytics.core.domain.Detector;

/** The metric of a recompute (DOC-23 §14.1). The shared run metrics go through {@code RunReporter}. */
public interface RecomputeMetrics {

    /** {@code pti_analytics_recompute_rows_total{detector, op="upserted"}}. */
    void upserted(Detector detector, long rows);

    /** {@code pti_analytics_recompute_rows_total{detector, op="deleted"}}. */
    void deleted(Detector detector, long rows);
}
