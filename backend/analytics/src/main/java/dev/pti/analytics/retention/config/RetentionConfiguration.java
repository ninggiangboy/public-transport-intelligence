package dev.pti.analytics.retention.config;

import dev.pti.analytics.retention.adapter.out.jdbc.JdbcInsightRetentionStore;
import dev.pti.analytics.retention.adapter.out.metrics.MicrometerRetentionMetrics;
import dev.pti.analytics.retention.application.PurgeInsight;
import dev.pti.analytics.retention.application.port.InsightRetentionStore;
import dev.pti.analytics.retention.application.port.RetentionMetrics;
import dev.pti.analytics.retention.domain.RetentionPolicy;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The retention of {@code insight} (DOC-23 §12.3). The {@link RetentionPolicy} comes from the host: the
 * {@code pti.retention.*} keys belong to the app that runs {@code OpsRetentionJob}.
 */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {

    @Bean
    InsightRetentionStore insightRetentionStore(JdbcClient jdbc) {
        return new JdbcInsightRetentionStore(jdbc);
    }

    @Bean
    RetentionMetrics retentionMetrics(MeterRegistry registry) {
        return new MicrometerRetentionMetrics(registry);
    }

    @Bean
    PurgeInsight purgeInsight(
            InsightRetentionStore store,
            RetentionMetrics metrics,
            TransactionRunner tx,
            BusinessClock clock,
            RetentionPolicy policy) {
        return new PurgeInsight(store, metrics, tx, clock, policy);
    }
}
