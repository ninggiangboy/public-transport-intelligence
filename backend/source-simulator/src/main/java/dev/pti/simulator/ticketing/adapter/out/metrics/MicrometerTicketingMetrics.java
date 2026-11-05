package dev.pti.simulator.ticketing.adapter.out.metrics;

import dev.pti.simulator.ticketing.application.port.TicketingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** {@link TicketingMetrics} on Micrometer (DOC-28 §3.1). The errors counter is registered at startup; the counters
 * of the transactions are created with their first use. */
public final class MicrometerTicketingMetrics implements TicketingMetrics {

    private final MeterRegistry registry;
    private final Counter errors;

    public MicrometerTicketingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.errors = Counter.builder("pti.sim.ticketing.errors").register(registry);
    }

    @Override
    public void transaction(String txnType, String action) {
        Counter.builder("pti.sim.ticket.transactions")
                .tag("txn_type", txnType)
                .tag("action", action)
                .register(registry)
                .increment();
    }

    @Override
    public void error() {
        errors.increment();
    }
}
