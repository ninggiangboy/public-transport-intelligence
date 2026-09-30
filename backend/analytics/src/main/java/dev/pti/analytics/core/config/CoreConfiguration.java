package dev.pti.analytics.core.config;

import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcOpenEpisodeCounts;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.OpenEpisodeCounts;
import dev.pti.analytics.core.application.port.TransactionLimits;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The adapters that every detector shares: advisory locks, transaction limits, open-episode counts, metrics. */
@Configuration(proxyBeanMethods = false)
class CoreConfiguration {

    @Bean
    AdvisoryLock advisoryLock(JdbcClient jdbc) {
        return new JdbcAdvisoryLock(jdbc);
    }

    @Bean
    TransactionLimits transactionLimits(JdbcClient jdbc) {
        return new JdbcTransactionLimits(jdbc);
    }

    @Bean
    OpenEpisodeCounts openEpisodeCounts(JdbcClient jdbc) {
        return new JdbcOpenEpisodeCounts(jdbc);
    }

    @Bean
    AnalyticsMetrics analyticsMetrics(MeterRegistry registry) {
        return new MicrometerAnalyticsMetrics(registry);
    }
}
