package dev.pti.analytics.core.adapter.out.metrics;

import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AnalyticsMetrics} on Micrometer. The names are the dotted forms of DOC-23 §14.1
 * ({@code pti.analytics.runs} is {@code pti_analytics_runs_total} in Prometheus). Meters are created with their first
 * use, so an app that never runs analytics exposes none of them. Histogram buckets come from
 * {@code management.metrics.distribution.slo.*} (DOC-28 §2).
 */
public class MicrometerAnalyticsMetrics implements AnalyticsMetrics {

    private final MeterRegistry registry;
    private final Map<Detector, AtomicLong> openEpisodes = new ConcurrentHashMap<>();

    public MicrometerAnalyticsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(Detector detector, Trigger trigger, Outcome outcome) {
        Counter.builder("pti.analytics.runs")
                .tag("detector", detector.tag())
                .tag("trigger", trigger.tag())
                .tag("outcome", outcome.tag())
                .register(registry)
                .increment();
    }

    @Override
    public void runDuration(Detector detector, Duration duration) {
        Timer.builder("pti.analytics.run")
                .tag("detector", detector.tag())
                .register(registry)
                .record(duration);
    }

    @Override
    public void dispatchDelay(Duration delay) {
        Timer.builder("pti.analytics.dispatch.delay").register(registry).record(delay);
    }

    @Override
    public void lateBatch(Detector detector) {
        Counter.builder("pti.analytics.late.batches")
                .tag("detector", detector.tag())
                .register(registry)
                .increment();
    }

    @Override
    public void skippedTicks(Detector detector, long count) {
        Counter.builder("pti.analytics.skipped.ticks")
                .tag("detector", detector.tag())
                .register(registry)
                .increment(count);
    }

    @Override
    public void openEpisodes(Detector detector, long count) {
        openEpisodes
                .computeIfAbsent(detector, d -> {
                    AtomicLong value = new AtomicLong();
                    Gauge.builder("pti.analytics.open.episodes", value, AtomicLong::get)
                            .tag("detector", d.tag())
                            .register(registry);
                    return value;
                })
                .set(count);
    }

    @Override
    public void dropped() {
        Counter.builder("pti.analytics.dropped").register(registry).increment();
    }
}
