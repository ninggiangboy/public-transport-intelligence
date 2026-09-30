package dev.pti.analytics.disruption.application.port;

import dev.pti.analytics.disruption.domain.CloseReason;

/** The metrics of the disruption detector beyond the shared ones of {@code AnalyticsMetrics} (DOC-23 §14.1). */
public interface DisruptionMetrics {

    /** {@code pti_analytics_episodes_total{detector="disruption", event="opened", reason="none"}}. */
    void episodeOpened();

    /** {@code pti_analytics_episodes_total{detector="disruption", event="closed", reason=...}}. */
    void episodeClosed(CloseReason reason);
}
