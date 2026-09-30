package dev.pti.analytics.bunching.config;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingEpisodeWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingStateStore;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader;
import dev.pti.analytics.bunching.adapter.out.metrics.MicrometerBunchingMetrics;
import dev.pti.analytics.bunching.application.BunchingDetector;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.application.port.BunchingMetrics;
import dev.pti.analytics.bunching.application.port.BunchingStateStore;
import dev.pti.analytics.bunching.application.port.VehicleHistoryReader;
import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bunching detection (DOC-23 §5). The detector is a {@code RouteDetector} bean: the dispatcher and the tick of the
 * host app pick it up from there. {@code pti.analytics.bunching.enabled=false} removes it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pti.analytics.bunching.enabled", havingValue = "true", matchIfMissing = true)
class BunchingConfiguration {

    @Bean
    VehicleHistoryReader vehicleHistoryReader(JdbcClient jdbc) {
        return new JdbcVehicleHistoryReader(jdbc);
    }

    @Bean
    BunchingStateStore bunchingStateStore(JdbcClient jdbc) {
        return new JdbcBunchingStateStore(jdbc);
    }

    @Bean
    BunchingEpisodeWriter bunchingEpisodeWriter(JdbcClient jdbc) {
        return new JdbcBunchingEpisodeWriter(jdbc);
    }

    @Bean
    BunchingMetrics bunchingMetrics(MeterRegistry registry) {
        return new MicrometerBunchingMetrics(registry);
    }

    @Bean
    BunchingDetector bunchingDetector(
            BunchingStateStore store,
            VehicleHistoryReader history,
            BunchingEpisodeWriter episodes,
            AlertWriter alerts,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            AnalyticsMetrics metrics,
            BunchingMetrics bunchingMetrics,
            TransactionRunner tx,
            BusinessClock clock,
            AnalyticsProperties properties) {
        return new BunchingDetector(
                store,
                history,
                episodes,
                alerts,
                reference,
                lock,
                limits,
                metrics,
                bunchingMetrics,
                tx,
                clock,
                properties.bunching().toThresholds());
    }
}
