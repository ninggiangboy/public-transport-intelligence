package dev.pti.analytics.disruption.adapter.out.metrics;

import dev.pti.analytics.disruption.application.port.DisruptionMetrics;
import dev.pti.analytics.disruption.domain.CloseReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link DisruptionMetrics} on Micrometer. {@code pti.analytics.episodes} is {@code pti_analytics_episodes_total} in
 * Prometheus (DOC-23 §14.1). The meter is created with its first use.
 */
public class MicrometerDisruptionMetrics implements DisruptionMetrics {

    private static final String NAME = "pti.analytics.episodes";
    private static final String DETECTOR = "disruption";

    private final MeterRegistry registry;

    public MicrometerDisruptionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void episodeOpened() {
        count("opened", "none");
    }

    @Override
    public void episodeClosed(CloseReason reason) {
        count("closed", reason.name());
    }

    private void count(String event, String reason) {
        Counter.builder(NAME)
                .tag("detector", DETECTOR)
                .tag("event", event)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
