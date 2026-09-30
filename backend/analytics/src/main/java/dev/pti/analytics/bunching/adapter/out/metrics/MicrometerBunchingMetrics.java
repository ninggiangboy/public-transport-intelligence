package dev.pti.analytics.bunching.adapter.out.metrics;

import dev.pti.analytics.bunching.application.port.BunchingMetrics;
import dev.pti.analytics.bunching.domain.CloseReason;
import dev.pti.analytics.bunching.domain.PassSource;
import dev.pti.analytics.bunching.domain.SkipReason;
import dev.pti.analytics.core.domain.Detector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link BunchingMetrics} on Micrometer, in the dotted form of the names in DOC-23 §14.1
 * ({@code pti.analytics.bunching.evaluations} is {@code pti_analytics_bunching_evaluations_total} in Prometheus).
 * Meters are created with their first use.
 */
public class MicrometerBunchingMetrics implements BunchingMetrics {

    private final MeterRegistry registry;

    public MicrometerBunchingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void evaluated(PassSource source, long count) {
        evaluations("evaluated", source.tag(), count);
    }

    @Override
    public void skipped(SkipReason reason, long count) {
        evaluations("skipped", reason.tag(), count);
    }

    @Override
    public void episodeOpened() {
        episodes("opened", "none");
    }

    @Override
    public void episodeClosed(CloseReason reason) {
        episodes("closed", reason.tag());
    }

    private void evaluations(String result, String reason, long count) {
        Counter.builder("pti.analytics.bunching.evaluations")
                .tag("result", result)
                .tag("reason", reason)
                .register(registry)
                .increment(count);
    }

    private void episodes(String event, String reason) {
        Counter.builder("pti.analytics.episodes")
                .tag("detector", Detector.BUNCHING.tag())
                .tag("event", event)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
