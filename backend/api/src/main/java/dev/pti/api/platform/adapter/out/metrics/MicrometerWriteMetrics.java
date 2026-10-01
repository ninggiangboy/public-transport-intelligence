package dev.pti.api.platform.adapter.out.metrics;

import dev.pti.api.platform.application.port.WriteMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code pti_api_write_requests_total{operation, outcome}} (DOC-31 §15). */
@Component
public final class MicrometerWriteMetrics implements WriteMetrics {

    private final MeterRegistry registry;

    public MicrometerWriteMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recorded(String operation, String outcome) {
        Counter.builder("pti.api.write.requests")
                .description("Writes of operators")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
