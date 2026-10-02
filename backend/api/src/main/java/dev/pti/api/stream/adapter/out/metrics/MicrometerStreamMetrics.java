package dev.pti.api.stream.adapter.out.metrics;

import dev.pti.api.stream.application.port.StreamMetrics;
import dev.pti.api.stream.domain.CloseReason;
import dev.pti.api.stream.domain.ReplayKind;
import dev.pti.common.events.UiChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * {@link StreamMetrics} on Micrometer, with the Prometheus names of DOC-28 §3.5: {@code pti.api.sse.*} counters,
 * {@code pti.api.publish.to.emit} and {@code pti.end.to.end.latency} as histograms whose buckets come from
 * {@code management.metrics.distribution.slo}.
 */
public final class MicrometerStreamMetrics implements StreamMetrics {

    private final MeterRegistry registry;

    public MicrometerStreamMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void opened(ReplayKind replay) {
        Counter.builder("pti.api.sse.connections.opened")
                .tag("replay", lower(replay.name()))
                .register(registry)
                .increment();
    }

    @Override
    public void closed(CloseReason reason) {
        Counter.builder("pti.api.sse.connections.closed")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    @Override
    public void emitted(UiChannel channel, String type) {
        Counter.builder("pti.api.sse.events.emitted")
                .tag("channel", channel.wireName())
                .tag("type", type)
                .register(registry)
                .increment();
    }

    @Override
    public void dropped(String reason, int frames) {
        Counter.builder("pti.api.sse.dropped")
                .tag("reason", reason)
                .register(registry)
                .increment(frames);
    }

    @Override
    public void publishToEmit(UiChannel channel, Duration latency) {
        Timer.builder("pti.api.publish.to.emit")
                .tag("channel", channel.wireName())
                .register(registry)
                .record(latency);
    }

    @Override
    public void endToEnd(UiChannel channel, Duration latency) {
        Timer.builder("pti.end.to.end.latency")
                .tag("channel", channel.wireName())
                .register(registry)
                .record(latency);
    }

    @Override
    public void invalidEvent() {
        Counter.builder("pti.api.sse.invalid.events").register(registry).increment();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
