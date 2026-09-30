package dev.pti.api.system.adapter.out.metrics;

import dev.pti.api.system.application.port.FreshnessMetrics;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * {@code pti_source_last_event_age_seconds{source}} (DOC-28 §3.5, DR-71): {@code businessNow − lastEventAt}, worked
 * out at each scrape from the last probe result, so it keeps growing between probes. A source that never had data has
 * no series, and neither has any source while the database cannot be read.
 */
@Component
public final class MicrometerFreshnessMetrics implements FreshnessMetrics {

    static final String GAUGE = "pti.source.last.event.age";

    private final MeterRegistry registry;
    private final BusinessClock clock;
    private final AtomicReference<Map<SourceKind, Instant>> lastEventAt = new AtomicReference<>(Map.of());
    private final Map<SourceKind, Gauge> gauges = new EnumMap<>(SourceKind.class);

    public MicrometerFreshnessMetrics(MeterRegistry registry, BusinessClock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public synchronized void publish(FreshnessSnapshot snapshot) {
        lastEventAt.set(snapshot.lastEventAt());
        for (SourceKind source : SourceKind.values()) {
            if (snapshot.lastEventAt().containsKey(source)) {
                gauges.computeIfAbsent(source, this::register);
            } else {
                remove(source);
            }
        }
    }

    @Override
    public synchronized void clear() {
        lastEventAt.set(Map.of());
        for (SourceKind source : SourceKind.values()) {
            remove(source);
        }
    }

    private Gauge register(SourceKind source) {
        return Gauge.builder(GAUGE, () -> ageSeconds(source))
                .baseUnit("seconds")
                .description("Business time minus the newest event time of a source")
                .tag("source", source.name())
                .register(registry);
    }

    private double ageSeconds(SourceKind source) {
        Instant last = lastEventAt.get().get(source);
        if (last == null) {
            return Double.NaN;
        }
        return Math.max(0, Duration.between(last, clock.instant()).toMillis() / 1000.0);
    }

    private void remove(SourceKind source) {
        Gauge gauge = gauges.remove(source);
        if (gauge != null) {
            registry.remove(gauge);
        }
    }
}
