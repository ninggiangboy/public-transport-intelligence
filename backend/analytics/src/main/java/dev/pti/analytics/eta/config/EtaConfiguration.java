package dev.pti.analytics.eta.config;

import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.eta.adapter.out.jdbc.JdbcEtaAggregateStore;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The historical ETA of DOC-23 §7: the planner and the per-route aggregator that {@code etl}'s
 * {@code EtaAggregationJob} calls, and that a recompute calls for a forced run.
 */
@Configuration(proxyBeanMethods = false)
class EtaConfiguration {

    @Bean
    EtaAggregateStore etaAggregateStore(JdbcClient jdbc) {
        return new JdbcEtaAggregateStore(jdbc);
    }

    @Bean
    EtaRunPlanner etaRunPlanner(EtaAggregateStore store, AnalyticsReferenceCache reference, BusinessClock clock) {
        return new EtaRunPlanner(store, reference, clock);
    }

    @Bean
    EtaAggregator etaAggregator(
            EtaAggregateStore store,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            AnalyticsProperties properties) {
        return new EtaAggregator(
                store,
                reference,
                lock,
                limits,
                tx,
                new RunReporter(metrics),
                properties.eta().toSettings());
    }
}
