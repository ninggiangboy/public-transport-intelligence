package dev.pti.analytics.bunching.application.port;

import dev.pti.analytics.bunching.domain.CloseReason;
import dev.pti.analytics.bunching.domain.PassSource;
import dev.pti.analytics.bunching.domain.SkipReason;

/** The metrics of bunching detection that the shared {@code AnalyticsMetrics} does not have (DOC-23 §14.1). */
public interface BunchingMetrics {

    /** {@code pti_analytics_bunching_evaluations_total{result="evaluated", reason=<source>}}. */
    void evaluated(PassSource source, long count);

    /** {@code pti_analytics_bunching_evaluations_total{result="skipped", reason=<reason>}}. */
    void skipped(SkipReason reason, long count);

    /** {@code pti_analytics_episodes_total{detector="bunching", event="opened", reason="none"}}. */
    void episodeOpened();

    /** {@code pti_analytics_episodes_total{detector="bunching", event="closed", reason=<reason>}}. */
    void episodeClosed(CloseReason reason);
}
