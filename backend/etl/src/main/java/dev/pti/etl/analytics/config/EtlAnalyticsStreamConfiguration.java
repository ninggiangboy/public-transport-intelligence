package dev.pti.etl.analytics.config;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.OpenEpisodeCounts;
import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.analytics.adapter.in.event.AnalyticsDispatcher;
import dev.pti.etl.analytics.adapter.in.scheduling.AnalyticsTick;
import dev.pti.etl.analytics.application.DispatchBatchAnalytics;
import dev.pti.etl.analytics.application.RunAnalyticsTick;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Analytics in {@code etl-stream} (DOC-23 §4, DOC-20 §8): the executor, the dispatcher that follows a micro-batch and
 * the tick. Off when {@code pti.analytics.enabled=false}, which is how the baseline container runs (DOC-23 §2.6): its
 * {@code exp_fact_*} tables are not an input of analytics.
 *
 * <p>The detectors are whatever {@link RouteDetector} beans exist: bunching and disruption add theirs in their own
 * slices, and until then the dispatcher and the tick have nothing to call.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stream")
@ConditionalOnProperty(name = "pti.analytics.enabled", havingValue = "true", matchIfMissing = true)
@EnableAsync
class EtlAnalyticsStreamConfiguration {

    /**
     * Two threads and a queue of 1,000. When the queue is full the oldest entry is dropped and counted: the tick and
     * the next micro-batch make up for it (DOC-20 §8, DOC-23 §15).
     */
    @Bean(name = "analyticsExecutor")
    ThreadPoolTaskExecutor analyticsExecutor(AnalyticsExecutorProperties executor, AnalyticsMetrics metrics) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(executor.threads());
        pool.setMaxPoolSize(executor.threads());
        pool.setQueueCapacity(executor.queueCapacity());
        pool.setThreadNamePrefix("analytics-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy() {
            @Override
            public void rejectedExecution(Runnable task, ThreadPoolExecutor owner) {
                metrics.dropped();
                super.rejectedExecution(task, owner);
            }
        });
        // DOC-20 §10: on shutdown a run in progress may finish for up to 5 seconds; the rest is dropped.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(5);
        return pool;
    }

    @Bean
    DispatchBatchAnalytics dispatchBatchAnalytics(
            ObjectProvider<RouteDetector> detectors,
            AnalyticsMetrics metrics,
            AnalyticsEventSink sink,
            BusinessClock clock) {
        return new DispatchBatchAnalytics(detectors.orderedStream().toList(), metrics, sink, clock);
    }

    @Bean
    RunAnalyticsTick runAnalyticsTick(
            ObjectProvider<RouteDetector> detectors,
            AnalyticsMetrics metrics,
            OpenEpisodeCounts openEpisodes,
            AnalyticsEventSink sink,
            BusinessClock clock) {
        List<RouteDetector> all = detectors.orderedStream().toList();
        return new RunAnalyticsTick(all, metrics, openEpisodes, sink, clock);
    }

    @Bean
    AnalyticsDispatcher analyticsDispatcher(DispatchBatchAnalytics dispatch, ObjectProvider<Tracer> tracer) {
        return new AnalyticsDispatcher(dispatch, tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    AnalyticsTick analyticsTick(
            RunAnalyticsTick tick, ThreadPoolTaskExecutor analyticsExecutor, ObjectProvider<Tracer> tracer) {
        return new AnalyticsTick(tick, analyticsExecutor, tracer.getIfAvailable(() -> Tracer.NOOP));
    }
}
