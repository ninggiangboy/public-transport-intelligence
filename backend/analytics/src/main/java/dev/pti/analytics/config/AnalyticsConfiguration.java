package dev.pti.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code pti.analytics.*}. The app that runs analytics picks up this and the feature configurations by scanning
 * {@code dev.pti.analytics}; it supplies what the library does not own: {@code JdbcClient}, {@code MeterRegistry},
 * {@code TransactionRunner}, {@code BusinessClock} and {@code ActiveFeedVersion}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
class AnalyticsConfiguration {}
