package dev.pti.etl.analytics.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.etl.analytics.executor.*} (DOC-20 §8, §11): the threads and the queue of the executor that runs
 * analytics after a micro-batch. Defaults are in {@code application.yml}.
 */
@ConfigurationProperties("pti.etl.analytics.executor")
@Validated
public record AnalyticsExecutorProperties(
        @Min(1) int threads, @Min(1) int queueCapacity) {}
