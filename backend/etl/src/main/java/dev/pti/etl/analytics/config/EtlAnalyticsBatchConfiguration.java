package dev.pti.etl.analytics.config;

import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.otp.application.OtpRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.recompute.application.AnalyticsRecomputeService;
import dev.pti.analytics.retention.application.PurgeInsight;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.analytics.adapter.in.batch.AnalyticsJobParameterCheck;
import dev.pti.etl.analytics.adapter.in.batch.EtaAggregationTasklet;
import dev.pti.etl.analytics.adapter.in.batch.OtpScorecardTasklet;
import dev.pti.etl.analytics.adapter.in.batch.PurgeInsightTasklet;
import dev.pti.etl.analytics.adapter.in.batch.RecomputeAnalyticsTasklet;
import dev.pti.etl.analytics.adapter.in.batch.RecomputePlans;
import dev.pti.etl.analytics.adapter.in.scheduling.AnalyticsJobSchedules;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.JobParameterCheck;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.config.GtfsProperties;
import dev.pti.etl.config.RetentionProperties;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The analytics jobs of {@code etl-batch} (DOC-23 §4.3, §11.5, DOC-19 §2): {@code EtaAggregationJob},
 * {@code OtpScorecardJob}, {@code AnalyticsRecomputeJob}, their triggers and request checks, the {@code purgeInsight}
 * step that {@code OpsRetentionJob} runs after {@code purgeOps}, and the {@code recomputeAnalytics} step that
 * {@code RawZoneReplayJob} runs after {@code replayRecords}. The tasklets only call {@code analytics} use cases.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
class EtlAnalyticsBatchConfiguration {

    /** Rows per delete: one transaction each, as every retention step (DOC-18 §5). */
    static final int PURGE_BATCH = 5000;

    @Bean(name = "EtaAggregationJob")
    Job etaAggregationJob(BatchSteps steps, EtaRunPlanner planner, EtaAggregator aggregator, BusinessClock clock) {
        return steps.job(PtiJob.ETA_AGGREGATION)
                .start(steps.tasklet("aggregateEta", new EtaAggregationTasklet(planner, aggregator, clock)))
                .build();
    }

    @Bean(name = "OtpScorecardJob")
    Job otpScorecardJob(BatchSteps steps, OtpRunPlanner planner, OtpScorecardCalculator calculator) {
        return steps.job(PtiJob.OTP_SCORECARD)
                .start(steps.tasklet("computeOtp", new OtpScorecardTasklet(planner, calculator)))
                .build();
    }

    @Bean(name = "AnalyticsRecomputeJob")
    Job analyticsRecomputeJob(BatchSteps steps, AnalyticsRecomputeService service) {
        return steps.job(PtiJob.ANALYTICS_RECOMPUTE)
                .start(steps.tasklet("recompute", new RecomputeAnalyticsTasklet(service, RecomputePlans.job(service))))
                .build();
    }

    /** The analytics half of {@code RawZoneReplayJob} (DOC-22 §4.4), run only with {@code recomputeAnalytics=true}. */
    @Bean(name = "recomputeAnalyticsStep")
    Step recomputeAnalyticsStep(BatchSteps steps, AnalyticsRecomputeService service) {
        return steps.tasklet(
                "recomputeAnalytics",
                new RecomputeAnalyticsTasklet(service, RecomputePlans.replay(service, steps.jobRepository())));
    }

    /** The insight half of {@code OpsRetentionJob} (DOC-23 §12.3). */
    @Bean(name = "purgeInsightStep")
    Step purgeInsightStep(BatchSteps steps, PurgeInsight purge) {
        return steps.tasklet("purgeInsight", new PurgeInsightTasklet(purge, PURGE_BATCH));
    }

    @Bean
    AnalyticsJobSchedules analyticsJobSchedules(PtiJobLauncher launcher, BusinessClock clock, GtfsProperties gtfs) {
        return new AnalyticsJobSchedules(launcher, clock, gtfs.staticFeed().zone());
    }

    /** A request with parameters the jobs cannot run is rejected before an execution exists (DOC-23 §15). */
    @Bean
    JobParameterCheck analyticsJobParameterCheck(
            BusinessClock clock, GtfsProperties gtfs, RetentionProperties retention) {
        return new AnalyticsJobParameterCheck(clock, gtfs.staticFeed().zone(), retention.tripUpdate());
    }
}
