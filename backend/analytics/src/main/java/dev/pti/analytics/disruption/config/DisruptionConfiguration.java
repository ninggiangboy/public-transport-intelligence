package dev.pti.analytics.disruption.config;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.disruption.adapter.out.jdbc.JdbcDisruptionStore;
import dev.pti.analytics.disruption.adapter.out.metrics.MicrometerDisruptionMetrics;
import dev.pti.analytics.disruption.application.DisruptionDetector;
import dev.pti.analytics.disruption.application.port.DisruptionMetrics;
import dev.pti.analytics.disruption.application.port.DisruptionStore;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The disruption detector of DOC-23 §6. It is a {@link RouteDetector} bean, which is how the dispatcher and the tick of
 * {@code etl-stream} find it; with {@code pti.analytics.disruption.enabled=false} the bean exists but answers no route
 * and needs no tick.
 */
@Configuration(proxyBeanMethods = false)
class DisruptionConfiguration {

    @Bean
    DisruptionStore disruptionStore(JdbcClient jdbc) {
        return new JdbcDisruptionStore(jdbc);
    }

    @Bean
    DisruptionMetrics disruptionMetrics(MeterRegistry registry) {
        return new MicrometerDisruptionMetrics(registry);
    }

    @Bean
    RouteDetector disruptionDetector(
            AnalyticsProperties properties,
            DisruptionStore store,
            AnalyticsReferenceCache reference,
            AlertWriter alertWriter,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            DisruptionMetrics disruptionMetrics,
            BusinessClock clock) {
        return new DisruptionDetector(
                properties.disruption().enabled(),
                properties.disruption().toThresholds(),
                store,
                reference,
                alertWriter,
                lock,
                limits,
                tx,
                metrics,
                disruptionMetrics,
                clock);
    }
}
