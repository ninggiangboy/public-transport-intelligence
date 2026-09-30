package dev.pti.analytics.core.application.port;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import java.time.Duration;

/**
 * The metrics that every detector shares (DOC-23 §14.1). Metrics of one detector (episodes, evaluations) are ports of
 * their own feature.
 */
public interface AnalyticsMetrics {

    /** {@code pti_analytics_runs_total}: every unit of work, including {@code noop}. */
    void run(Detector detector, Trigger trigger, Outcome outcome);

    /** {@code pti_analytics_run_seconds}: only units that had work, not {@code noop}. */
    void runDuration(Detector detector, Duration duration);

    /** {@code pti_analytics_dispatch_delay_seconds}: from the commit of a micro-batch to the start of the run. */
    void dispatchDelay(Duration delay);

    /** {@code pti_analytics_late_batches_total}: once per micro-batch and detector (DOC-23 §2.2). */
    void lateBatch(Detector detector);

    /** {@code pti_analytics_skipped_ticks_total}: grid points a detector skipped because of the catch-up limit. */
    void skippedTicks(Detector detector, long count);

    /** {@code pti_analytics_open_episodes}: counted from the database at each tick. */
    void openEpisodes(Detector detector, long count);

    /** {@code pti_analytics_dropped_total}: a micro-batch event the analytics executor dropped because it was full. */
    void dropped();
}
