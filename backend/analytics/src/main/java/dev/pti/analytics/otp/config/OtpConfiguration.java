package dev.pti.analytics.otp.config;

import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.otp.adapter.out.jdbc.JdbcOtpScorecardStore;
import dev.pti.analytics.otp.application.OtpRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.otp.application.port.OtpScorecardStore;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The on-time performance scorecard of DOC-23 §8: the planner and the per-day calculator that {@code etl}'s
 * {@code OtpScorecardJob} calls, and that a recompute calls for the days of its plan.
 */
@Configuration(proxyBeanMethods = false)
class OtpConfiguration {

    @Bean
    OtpScorecardStore otpScorecardStore(JdbcClient jdbc) {
        return new JdbcOtpScorecardStore(jdbc);
    }

    @Bean
    OtpRunPlanner otpRunPlanner(
            AnalyticsReferenceCache reference, BusinessClock clock, AnalyticsProperties properties) {
        return new OtpRunPlanner(reference, clock, properties.otp().toSettings());
    }

    @Bean
    OtpScorecardCalculator otpScorecardCalculator(
            OtpScorecardStore store,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            BusinessClock clock,
            AnalyticsProperties properties) {
        return new OtpScorecardCalculator(
                store,
                reference,
                lock,
                limits,
                tx,
                new RunReporter(metrics),
                clock,
                properties.otp().toSettings());
    }
}
