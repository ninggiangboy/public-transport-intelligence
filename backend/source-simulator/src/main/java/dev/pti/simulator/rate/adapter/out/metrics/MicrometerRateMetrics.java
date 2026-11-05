package dev.pti.simulator.rate.adapter.out.metrics;

import dev.pti.simulator.rate.application.port.RateMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;

/** {@link RateMetrics} on Micrometer (DOC-28 §3.1). */
public final class MicrometerRateMetrics implements RateMetrics {

    private final AtomicReference<Double> gtfsRt = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ticketing = new AtomicReference<>(0.0);

    public MicrometerRateMetrics(MeterRegistry registry) {
        Gauge.builder("pti.sim.rate.multiplier", gtfsRt, AtomicReference::get)
                .tag("stream", "gtfs-rt")
                .register(registry);
        Gauge.builder("pti.sim.rate.multiplier", ticketing, AtomicReference::get)
                .tag("stream", "ticketing")
                .register(registry);
    }

    @Override
    public void multiplier(double newGtfsRt, double newTicketing) {
        gtfsRt.set(newGtfsRt);
        ticketing.set(newTicketing);
    }
}
