package dev.pti.spike.batch;

import java.util.List;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Builds the same job on both chunk step implementations of Spring Batch 6. */
@Component
public class SpikeJobs {

    public enum Variant {
        /** ChunkOrientedStepBuilder, the new implementation in 6.0. */
        NEW,
        /** SimpleStepBuilder/FaultTolerantStepBuilder, deprecated for removal in 6.0. */
        LEGACY
    }

    /** Skip only data errors, like RatioSkipPolicy for ErrorKind.DATA (DOC-19 §4.4). */
    static final SkipPolicy DATA_ONLY = (t, skipCount) -> {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DataIntegrityViolationException) {
                return true;
            }
        }
        return false;
    };

    private final JobRepository jobRepository;
    private final PlatformTransactionManager tx;
    private final JdbcTemplate jdbc;

    public SpikeJobs(JobRepository jobRepository, PlatformTransactionManager tx, JdbcTemplate jdbc) {
        this.jobRepository = jobRepository;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** DOC-19 §4.4 shape with short intervals for the test. */
    private static ExponentialBackOffPolicy backOff() {
        var policy = new ExponentialBackOffPolicy();
        policy.setInitialInterval(100);
        policy.setMultiplier(2);
        policy.setMaxInterval(1000);
        return policy;
    }

    public Job job(String name, Variant variant, List<Item> items, int chunkSize, FaultPlan faults) {
        return new JobBuilder(name, jobRepository)
                .start(step(name + "-step", variant, items, chunkSize, faults))
                .build();
    }

    @SuppressWarnings("removal")
    private Step step(String name, Variant variant, List<Item> items, int chunkSize, FaultPlan faults) {
        var reader = new ItemListReader(items);
        var writer = new FactWriter(jdbc, faults, variant.name());
        var skipListener = new DlqSkipListener(jdbc);
        return switch (variant) {
            case NEW ->
                new StepBuilder(name, jobRepository)
                        .<Item, Item>chunk(chunkSize)
                        .transactionManager(tx)
                        .reader(reader)
                        .writer(writer)
                        .faultTolerant()
                        .skipPolicy(DATA_ONLY)
                        .skipListener(skipListener)
                        .build();
            case LEGACY ->
                new StepBuilder(name, jobRepository)
                        .<Item, Item>chunk(chunkSize, tx)
                        .reader(reader)
                        .writer(writer)
                        .faultTolerant()
                        .processorNonTransactional()
                        .skipPolicy(DATA_ONLY)
                        .retry(TransientDataAccessException.class)
                        .retryLimit(3)
                        .backOffPolicy(backOff())
                        .listener(skipListener)
                        .build();
        };
    }
}
