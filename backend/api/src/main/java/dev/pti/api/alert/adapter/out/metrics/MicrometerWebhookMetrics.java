package dev.pti.api.alert.adapter.out.metrics;

import dev.pti.api.alert.application.port.WebhookMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code pti_alert_webhook_total{outcome}} (DOC-28, DOC-32 E-80). */
@Component
public final class MicrometerWebhookMetrics implements WebhookMetrics {

    private final MeterRegistry registry;

    public MicrometerWebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void outcome(String outcome) {
        Counter.builder("pti.alert.webhook")
                .description("Notices of the Alertmanager webhook by what was done with them")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
