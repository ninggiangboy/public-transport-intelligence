package dev.pti.simulator.emit.adapter.out.metrics;

import dev.pti.simulator.emit.application.port.ResendMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link ResendMetrics} on Micrometer (DOC-28 §3.1). The gauge is registered once and reads the reported value. */
public final class MicrometerResendMetrics implements ResendMetrics {

    private final Counter full;
    private final AtomicInteger depth = new AtomicInteger();

    public MicrometerResendMetrics(MeterRegistry registry) {
        this.full = Counter.builder("pti.sim.emissions.skipped")
                .tag("reason", "resend_queue_full")
                .register(registry);
        Gauge.builder("pti.sim.resend.queue.depth", depth, AtomicInteger::get).register(registry);
    }

    @Override
    public void skipped() {
        full.increment();
    }

    @Override
    public void depth(int depth) {
        this.depth.set(depth);
    }
}
