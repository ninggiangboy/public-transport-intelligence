package dev.pti.analytics.reference.config;

import dev.pti.analytics.reference.adapter.out.jdbc.JdbcAnalyticsReferenceCache;
import dev.pti.analytics.reference.application.port.ActiveFeedVersion;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The schedule data of the ACTIVE feed (DOC-23 §3). */
@Configuration(proxyBeanMethods = false)
class ReferenceConfiguration {

    @Bean
    AnalyticsReferenceCache analyticsReferenceCache(JdbcClient jdbc, ActiveFeedVersion activeFeed) {
        return new JdbcAnalyticsReferenceCache(jdbc, activeFeed);
    }
}
