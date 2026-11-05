package dev.pti.etl.config;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.adapter.in.batch.BatchChunkWriter;
import dev.pti.etl.batch.adapter.in.batch.BatchIdStepListener;
import dev.pti.etl.batch.adapter.in.batch.BatchSteps;
import dev.pti.etl.batch.adapter.in.batch.DeadLetterSkipListener;
import dev.pti.etl.batch.adapter.in.batch.FaultStepListener;
import dev.pti.etl.batch.adapter.in.batch.JobRequestListener;
import dev.pti.etl.batch.adapter.in.batch.MessageProcessorRouter;
import dev.pti.etl.batch.adapter.in.batch.PtiJobLauncher;
import dev.pti.etl.batch.adapter.in.batch.RatioSkipPolicy;
import dev.pti.etl.batch.adapter.in.batch.TransientRetryPolicy;
import dev.pti.etl.batch.adapter.in.scheduling.BatchSchedules;
import dev.pti.etl.batch.adapter.out.jdbc.JobRequests;
import dev.pti.etl.batch.adapter.out.jdbc.StaleExecutionRecoverer;
import dev.pti.etl.batch.adapter.out.metrics.MicrometerBatchRequestMetrics;
import dev.pti.etl.batch.application.HandleJobRequests;
import dev.pti.etl.batch.application.port.BatchRequestMetrics;
import dev.pti.etl.batch.application.port.BatchStartupTask;
import dev.pti.etl.batch.application.port.JobParameterCheck;
import dev.pti.etl.batch.application.port.JobRequestStore;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.application.port.StaleExecutions;
import dev.pti.etl.core.application.MessageProcessors;
import dev.pti.etl.fault.application.port.FaultInjector;
import dev.pti.etl.reference.application.ReferenceDataHolder;
import dev.pti.etl.write.adapter.out.jdbc.FactChunkWriter;
import dev.pti.etl.write.application.port.DeadLetterWriter;
import dev.pti.etl.write.application.port.WriteMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.ExecutionContextSerializer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.JacksonExecutionContextStringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The {@code batch} profile (etl-batch): Spring Batch infrastructure, ShedLock, pollers and schedules (DOC-19 §3). */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class EtlBatchConfiguration {

    /** DOC-19 §3.3: at most three jobs at once on one pod; a full queue leaves requests PENDING. */
    @Bean
    @BatchTaskExecutor
    ThreadPoolTaskExecutor batchTaskExecutor(BatchJobProperties batch) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(batch.executor().coreSize());
        executor.setMaxPoolSize(batch.executor().maxSize());
        executor.setQueueCapacity(batch.executor().queueCapacity());
        executor.setThreadNamePrefix("pti-job-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /** DR-62: JSON in {@code BATCH_*_EXECUTION_CONTEXT}, readable by the ops console. */
    @Bean
    ExecutionContextSerializer executionContextSerializer() {
        return new JacksonExecutionContextStringSerializer();
    }

    /** DR-24 layer 1; {@code usingDbTime()} compares lock times on the database clock, not on each pod's. */
    @Bean
    LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .withTableName("ops.shedlock")
                .usingDbTime()
                .build());
    }

    @Bean
    LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }

    @Bean
    RatioSkipPolicy ratioSkipPolicy(ErrorClassifier classifier, EtlProperties etl) {
        return new RatioSkipPolicy(
                classifier, etl.batch().maxSkipRatio(), etl.batch().skipMinSample());
    }

    @Bean
    TransientRetryPolicy transientRetryPolicy(ErrorClassifier classifier, EtlProperties etl) {
        return new TransientRetryPolicy(classifier, etl.retry().maxAttempts());
    }

    @Bean
    ExponentialRandomBackOffPolicy batchBackOffPolicy(EtlProperties etl) {
        return TransientRetryPolicy.backOff(
                etl.retry().initialInterval(),
                etl.retry().multiplier(),
                etl.retry().maxInterval());
    }

    @Bean
    DeadLetterSkipListener deadLetterSkipListener(
            DeadLetterWriter deadLetters, MessageProcessors processors, WriteMetrics stats) {
        return new DeadLetterSkipListener(deadLetters, processors, stats);
    }

    @Bean
    BatchIdStepListener batchIdStepListener(JdbcTemplate jdbc) {
        return new BatchIdStepListener(jdbc);
    }

    @Bean
    FaultStepListener faultStepListener(FaultInjector faults) {
        return new FaultStepListener(faults);
    }

    @Bean
    JobRequests jobRequests(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new JobRequests(jdbc, tx);
    }

    @Bean
    JobRequestListener jobRequestListener(JobRequests requests) {
        return new JobRequestListener(requests);
    }

    @Bean
    BatchSteps batchSteps(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RatioSkipPolicy skipPolicy,
            TransientRetryPolicy retryPolicy,
            ExponentialRandomBackOffPolicy backOffPolicy,
            DeadLetterSkipListener deadLetterListener,
            BatchIdStepListener batchIdListener,
            FaultStepListener faultListener,
            JobRequestListener jobRequestListener,
            EtlProperties etl) {
        return new BatchSteps(
                jobRepository,
                transactionManager,
                skipPolicy,
                retryPolicy,
                backOffPolicy,
                deadLetterListener,
                batchIdListener,
                faultListener,
                List.of(jobRequestListener),
                etl.baseline().errorMode() == EtlProperties.ErrorMode.FAIL_BATCH);
    }

    @Bean
    BatchChunkWriter batchChunkWriter(FactChunkWriter writer, BusinessClock clock) {
        return new BatchChunkWriter(writer, clock);
    }

    @Bean
    MessageProcessorRouter messageProcessorRouter(
            MessageProcessors processors, BusinessClock clock, ReferenceDataHolder reference) {
        return new MessageProcessorRouter(processors, clock, reference);
    }

    @Bean
    PtiJobLauncher ptiJobLauncher(JobOperator jobOperator, JobRepository jobRepository, List<Job> jobs) {
        return new PtiJobLauncher(jobOperator, jobRepository, jobs);
    }

    @Bean
    BatchRequestMetrics batchRequestMetrics(MeterRegistry meters) {
        return new MicrometerBatchRequestMetrics(meters);
    }

    @Bean
    HandleJobRequests handleJobRequests(
            JobRequestStore requests,
            LaunchJob launcher,
            BusinessClock clock,
            ThreadPoolTaskExecutor batchTaskExecutor,
            BatchRequestMetrics metrics,
            ObjectProvider<JobParameterCheck> checks) {
        return new HandleJobRequests(
                requests,
                launcher,
                clock,
                () -> hasRoom(batchTaskExecutor),
                metrics,
                checks.orderedStream().toList());
    }

    private static boolean hasRoom(ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        return pool.getActiveCount() < pool.getMaximumPoolSize()
                || pool.getQueue().remainingCapacity() > 0;
    }

    @Bean
    StaleExecutions staleExecutionRecoverer(
            JobRepository jobRepository,
            JobOperator jobOperator,
            PtiJobLauncher launcher,
            JobRequests requests,
            JdbcTemplate jdbc,
            BatchJobProperties batch,
            MeterRegistry meters) {
        return new StaleExecutionRecoverer(
                jobRepository,
                jobOperator,
                launcher,
                requests,
                jdbc,
                batch.staleAfter(),
                Clock.systemDefaultZone(),
                meters);
    }

    @Bean
    BatchSchedules batchSchedules(
            PtiJobLauncher launcher,
            HandleJobRequests handleJobRequests,
            StaleExecutions recoverer,
            LockingTaskExecutor locks,
            BusinessClock clock,
            GtfsProperties gtfs,
            ObjectProvider<BatchStartupTask> startupTasks) {
        return new BatchSchedules(
                launcher,
                handleJobRequests,
                recoverer,
                locks,
                clock,
                gtfs.staticFeed().zone(),
                startupTasks.orderedStream().toList());
    }
}
