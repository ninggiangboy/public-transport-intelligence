package dev.pti.simulator.emit.adapter.out.metrics;

import dev.pti.simulator.emit.application.port.EmitterMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** {@link EmitterMetrics} on Micrometer (DOC-28 §3.1). The gauges are registered once and read the reported values. */
public final class MicrometerEmitterMetrics implements EmitterMetrics {

    private final AtomicLong vehicles = new AtomicLong();
    private final AtomicLong trips = new AtomicLong();
    private final AtomicLong synthetic = new AtomicLong();
    private final AtomicReference<Double> tickLag = new AtomicReference<>(0.0);

    public MicrometerEmitterMetrics(MeterRegistry registry) {
        Gauge.builder("pti.sim.active.vehicles", vehicles, AtomicLong::get).register(registry);
        Gauge.builder("pti.sim.active.trips", trips, AtomicLong::get).register(registry);
        Gauge.builder("pti.sim.synthetic.vehicles", synthetic, AtomicLong::get).register(registry);
        Gauge.builder("pti.sim.tick.lag", tickLag, AtomicReference::get)
                .baseUnit("seconds")
                .register(registry);
    }

    @Override
    public void active(int activeVehicles, int activeTrips) {
        vehicles.set(activeVehicles);
        trips.set(activeTrips);
    }

    @Override
    public void syntheticVehicles(int count) {
        synthetic.set(count);
    }

    @Override
    public void tickLag(double seconds) {
        tickLag.set(seconds);
    }
}
