package dev.pti.etl.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.dq.adapter.in.batch.DataQualityTasklet;
import dev.pti.etl.dq.adapter.out.jdbc.DqCheckResults;
import dev.pti.etl.dq.adapter.out.jdbc.JdbcPostWriteChecks;
import dev.pti.etl.dq.adapter.out.metrics.DqMetrics;
import dev.pti.etl.dq.application.RunDataQualityChecks;
import dev.pti.etl.dq.application.port.DqCheckMetrics;
import dev.pti.etl.dq.application.port.PostWriteChecks;
import dev.pti.etl.dq.application.port.PostWriteRuleStore;
import dev.pti.etl.gtfs.FeedVersions;
import dev.pti.etl.metrics.adapter.out.micrometer.OpsGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** {@code DataQualityJob}: the post-write rules of DOC-16 §3; {@code pti.dq.post-write.enabled=false} removes it. */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@ConditionalOnBooleanProperty(name = "pti.dq.post-write.enabled", matchIfMissing = true)
public class DataQualityConfiguration {

    @Bean
    DqCheckResults dqCheckResults(JdbcTemplate jdbc) {
        return new DqCheckResults(jdbc);
    }

    @Bean
    DqMetrics dqMetrics(DqCheckResults results, MeterRegistry meters, DqProperties dq) {
        return new DqMetrics(results, meters, dq::enabled);
    }

    @Bean
    PostWriteChecks postWriteChecks(
            NamedParameterJdbcTemplate named,
            PlatformTransactionManager transactionManager,
            DqProperties dq,
            PostWriteRuleStore store,
            DqCheckMetrics metrics) {
        return new JdbcPostWriteChecks(named, transactionManager, dq.postWrite().statementTimeout(), store, metrics);
    }

    @Bean
    RunDataQualityChecks runDataQualityChecks(
            PostWriteRuleStore store,
            PostWriteChecks checks,
            BusinessClock clock,
            GtfsProperties gtfs,
            DqProperties dq,
            FeedVersions versions) {
        return new RunDataQualityChecks(
                store,
                checks,
                clock,
                gtfs.staticFeed().zone(),
                dq.refundGrace(),
                dq::enabled,
                () -> versions.activeId().orElse(-1L));
    }

    @Bean
    OpsGauges opsGauges(JdbcTemplate jdbc, BusinessClock clock, MeterRegistry meters) {
        return new OpsGauges(jdbc, clock, meters);
    }

    @Bean(name = "DataQualityJob")
    Job dataQualityJob(BatchSteps steps, RunDataQualityChecks checks) {
        DataQualityTasklet tasklet = new DataQualityTasklet(checks);
        return steps.job(PtiJob.DATA_QUALITY)
                .start(steps.tasklet("runDueRules", tasklet))
                .build();
    }
}
