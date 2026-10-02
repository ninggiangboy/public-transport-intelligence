package dev.pti.analytics.recompute.config;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.application.port.BunchingStateStore;
import dev.pti.analytics.bunching.application.port.VehicleHistoryReader;
import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.disruption.application.port.DisruptionStore;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcBunchingRecomputeStore;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcDisruptionRecomputeStore;
import dev.pti.analytics.recompute.adapter.out.jdbc.JdbcRecomputeScopes;
import dev.pti.analytics.recompute.adapter.out.metrics.MicrometerRecomputeMetrics;
import dev.pti.analytics.recompute.application.AnalyticsRecomputeService;
import dev.pti.analytics.recompute.application.BunchingRecompute;
import dev.pti.analytics.recompute.application.DetectorRecompute;
import dev.pti.analytics.recompute.application.DetectorRecomputeService;
import dev.pti.analytics.recompute.application.DisruptionRecompute;
import dev.pti.analytics.recompute.application.EtaRecompute;
import dev.pti.analytics.recompute.application.OtpRecompute;
import dev.pti.analytics.recompute.application.port.BunchingRecomputeStore;
import dev.pti.analytics.recompute.application.port.DisruptionRecomputeStore;
import dev.pti.analytics.recompute.application.port.RecomputeMetrics;
import dev.pti.analytics.recompute.application.port.RecomputeScopes;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The recompute of DOC-23 §11: one {@link DetectorRecompute} per detector and the {@link AnalyticsRecomputeService}
 * that {@code etl}'s replay step and {@code AnalyticsRecomputeJob} call. Ticketing has none yet (P6-05); its items are
 * left out of every plan. With {@code pti.analytics.bunching.enabled=false} bunching has no recompute either.
 */
@Configuration(proxyBeanMethods = false)
class RecomputeConfiguration {

    @Bean
    RecomputeScopes recomputeScopes(JdbcClient jdbc) {
        return new JdbcRecomputeScopes(jdbc);
    }

    @Bean
    BunchingRecomputeStore bunchingRecomputeStore(JdbcClient jdbc) {
        return new JdbcBunchingRecomputeStore(jdbc);
    }

    @Bean
    DisruptionRecomputeStore disruptionRecomputeStore(JdbcClient jdbc) {
        return new JdbcDisruptionRecomputeStore(jdbc);
    }

    @Bean
    RecomputeMetrics recomputeMetrics(MeterRegistry registry) {
        return new MicrometerRecomputeMetrics(registry);
    }

    @Bean
    @ConditionalOnProperty(name = "pti.analytics.bunching.enabled", havingValue = "true", matchIfMissing = true)
    DetectorRecompute bunchingRecompute(
            AnalyticsProperties properties,
            BunchingStateStore state,
            VehicleHistoryReader history,
            BunchingEpisodeWriter episodes,
            AlertWriter alerts,
            BunchingRecomputeStore store,
            RecomputeScopes scopes,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            BusinessClock clock) {
        return new BunchingRecompute(
                properties.bunching().toThresholds(),
                state,
                history,
                episodes,
                alerts,
                store,
                scopes,
                reference,
                lock,
                limits,
                tx,
                new RunReporter(metrics),
                clock);
    }

    @Bean
    DetectorRecompute disruptionRecompute(
            AnalyticsProperties properties,
            DisruptionStore disruption,
            DisruptionRecomputeStore store,
            RecomputeScopes scopes,
            AnalyticsReferenceCache reference,
            AlertWriter alerts,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            BusinessClock clock) {
        return new DisruptionRecompute(
                properties.disruption().enabled(),
                properties.disruption().toThresholds(),
                disruption,
                store,
                scopes,
                reference,
                alerts,
                lock,
                limits,
                tx,
                new RunReporter(metrics),
                clock);
    }

    @Bean
    DetectorRecompute etaRecompute(
            EtaRunPlanner planner,
            EtaAggregator aggregator,
            AnalyticsReferenceCache reference,
            TransactionRunner tx,
            TransactionLimits limits,
            BusinessClock clock) {
        return new EtaRecompute(planner, aggregator, reference, tx, limits, clock);
    }

    @Bean
    DetectorRecompute otpRecompute(
            OtpScorecardCalculator calculator, AnalyticsReferenceCache reference, BusinessClock clock) {
        return new OtpRecompute(calculator, reference, clock);
    }

    @Bean
    AnalyticsRecomputeService analyticsRecomputeService(List<DetectorRecompute> recomputes, RecomputeMetrics metrics) {
        return new DetectorRecomputeService(recomputes, metrics);
    }
}
