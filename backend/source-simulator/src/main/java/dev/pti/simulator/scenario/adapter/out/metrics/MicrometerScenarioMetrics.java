package dev.pti.simulator.scenario.adapter.out.metrics;

import dev.pti.simulator.scenario.application.port.ScenarioMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** {@link ScenarioMetrics} on Micrometer (DOC-28 §3.1). One gauge per scenario, created with its first use. */
public final class MicrometerScenarioMetrics implements ScenarioMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> active = new ConcurrentHashMap<>();

    public MicrometerScenarioMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void active(String scenario, int count) {
        active.computeIfAbsent(scenario, name -> {
                    AtomicLong value = new AtomicLong();
                    Gauge.builder("pti.sim.scenario.active", value, AtomicLong::get)
                            .tag("scenario", name)
                            .register(registry);
                    return value;
                })
                .set(count);
    }
}
