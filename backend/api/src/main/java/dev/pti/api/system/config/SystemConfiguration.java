package dev.pti.api.system.config;

import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.system.adapter.in.scheduling.FreshnessProbeScheduler;
import dev.pti.api.system.application.GetCurrentUser;
import dev.pti.api.system.application.GetFreshness;
import dev.pti.api.system.application.RefreshFreshness;
import dev.pti.api.system.application.port.FreshnessMetrics;
import dev.pti.api.system.application.port.FreshnessQuery;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessThresholds;
import dev.pti.common.time.BusinessClock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Wires the use cases of the {@code system} feature: {@code /me}, {@code /system/freshness} and the probe (DOC-32
 * §10). Its adapters (the probe's SQL, the snapshot cache, the gauge, {@code X-Data-As-Of}) are components: they name
 * platform adapters in their constructors, which a {@code config} class may not (A-14).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({FreshnessProperties.class, FreshnessProbeProperties.class})
class SystemConfiguration implements SchedulingConfigurer {

    private final FreshnessProbeProperties probe;
    private final ObjectProvider<RefreshFreshness> refresh;

    SystemConfiguration(FreshnessProbeProperties probe, ObjectProvider<RefreshFreshness> refresh) {
        this.probe = probe;
        this.refresh = refresh;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (probe.enabled()) {
            registrar.addFixedDelayTask(new FreshnessProbeScheduler(refresh.getObject()), probe.interval());
        }
    }

    @Bean
    RefreshFreshness refreshFreshness(
            FreshnessQuery query,
            FreshnessSnapshots snapshots,
            FreshnessMetrics metrics,
            ActiveFeedReader feeds,
            BusinessClock clock,
            FreshnessProperties properties) {
        return new RefreshFreshness(query, snapshots, metrics, feeds, clock, properties.insightInterval());
    }

    @Bean
    GetFreshness getFreshness(FreshnessSnapshots snapshots, BusinessClock clock, FreshnessProperties properties) {
        FreshnessProperties.StaleAfter staleAfter = properties.staleAfter();
        return new GetFreshness(
                snapshots,
                clock,
                new FreshnessThresholds(staleAfter.gtfsRt(), staleAfter.ticketing()),
                properties.maxProbeAge());
    }

    @Bean
    GetCurrentUser getCurrentUser() {
        return new GetCurrentUser();
    }
}
