package dev.pti.etl.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.dq.DataQualityTasklet;
import dev.pti.etl.dq.DqCheckResults;
import dev.pti.etl.dq.DqMetrics;
import dev.pti.etl.gtfs.FeedVersions;
import dev.pti.etl.metrics.OpsGauges;
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
    OpsGauges opsGauges(JdbcTemplate jdbc, BusinessClock clock, MeterRegistry meters) {
        return new OpsGauges(jdbc, clock, meters);
    }

    @Bean(name = "DataQualityJob")
    Job dataQualityJob(
            BatchSteps steps,
            NamedParameterJdbcTemplate named,
            DqCheckResults results,
            PlatformTransactionManager transactionManager,
            BusinessClock clock,
            GtfsProperties gtfs,
            DqProperties dq,
            FeedVersions versions,
            MeterRegistry meters) {
        DataQualityTasklet tasklet = new DataQualityTasklet(
                named,
                results,
                transactionManager,
                clock,
                gtfs.staticFeed().zone(),
                dq.refundGrace(),
                dq.postWrite().statementTimeout(),
                dq::enabled,
                () -> versions.activeId().orElse(-1L),
                meters);
        return steps.job(PtiJob.DATA_QUALITY)
                .start(steps.tasklet("runDueRules", tasklet))
                .build();
    }
}
