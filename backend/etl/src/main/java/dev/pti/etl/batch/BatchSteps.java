package dev.pti.etl.batch;

import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.WriteSet;
import java.util.List;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds jobs and steps with the listeners every one of them needs (DOC-19 §2, §5), so that no job can forget its
 * {@code batch_id} or the {@code job_request} update.
 */
public class BatchSteps {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final RatioSkipPolicy skipPolicy;
    private final TransientRetryPolicy retryPolicy;
    private final BackOffPolicy backOffPolicy;
    private final DeadLetterSkipListener deadLetterListener;
    private final BatchIdStepListener batchIdListener;
    private final FaultStepListener faultListener;
    private final List<JobExecutionListener> jobListeners;
    private final boolean failBatch;

    public BatchSteps(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RatioSkipPolicy skipPolicy,
            TransientRetryPolicy retryPolicy,
            BackOffPolicy backOffPolicy,
            DeadLetterSkipListener deadLetterListener,
            BatchIdStepListener batchIdListener,
            FaultStepListener faultListener,
            List<JobExecutionListener> jobListeners,
            boolean failBatch) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.skipPolicy = skipPolicy;
        this.retryPolicy = retryPolicy;
        this.backOffPolicy = backOffPolicy;
        this.deadLetterListener = deadLetterListener;
        this.batchIdListener = batchIdListener;
        this.faultListener = faultListener;
        this.jobListeners = List.copyOf(jobListeners);
        this.failBatch = failBatch;
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    /** A job builder with the shared job listeners ({@code job_request} status, MDC). */
    public JobBuilder job(PtiJob job) {
        return job(job.jobName());
    }

    /** As {@link #job(PtiJob)}, for a job outside the catalog. */
    public JobBuilder job(String name) {
        JobBuilder builder = new JobBuilder(name, jobRepository);
        jobListeners.forEach(builder::listener);
        return builder;
    }

    /**
     * The fault-tolerant chunk step of DOC-19 §5 on the legacy builder (DR-80): skip data errors into the dead-letter
     * queue, retry transient ones with back-off, scan item by item when the writer hits a data error. In baseline
     * {@code fail-batch} mode (DR-27) the step is not fault tolerant, so the first error fails it.
     */
    @SuppressWarnings("removal")
    public Step chunk(
            String name,
            int chunkSize,
            ItemReader<? extends InboundMessage> reader,
            ItemProcessor<InboundMessage, WriteSet> processor,
            ItemWriter<WriteSet> writer) {
        SimpleStepBuilder<InboundMessage, WriteSet> builder = new StepBuilder(name, jobRepository)
                .<InboundMessage, WriteSet>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer);
        // The typed overloads: listener(Object) only picks up annotated methods.
        builder.listener((StepExecutionListener) batchIdListener);
        builder.listener((StepExecutionListener) new SkipAwareExitListener());
        builder.listener((ChunkListener<?, ?>) faultListener);
        builder.listener((ItemProcessListener<Object, Object>) faultListener);
        builder.listener((ItemWriteListener<Object>) faultListener);
        if (failBatch) {
            return builder.build();
        }
        return builder.faultTolerant()
                .processorNonTransactional()
                .skipPolicy(skipPolicy)
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .listener(deadLetterListener)
                .build();
    }

    /** A tasklet step; it gets a {@code batch_id} too, so that every row it writes can be traced (DR-63). */
    public Step tasklet(String name, Tasklet tasklet) {
        return new StepBuilder(name, jobRepository)
                .tasklet(tasklet, transactionManager)
                .listener((StepExecutionListener) batchIdListener)
                .build();
    }
}
