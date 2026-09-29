package dev.pti.etl.gtfs;

import dev.pti.etl.batch.PtiJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;

/**
 * Job-level rules of {@code GtfsStaticLoadJob} (DOC-21 §1, §5, §8): two feeds are never loaded at once (the later
 * execution fails), the workspace goes when the job did not fail, and every run counts in {@code pti_gtfs_load_total}.
 */
public class GtfsJobListener implements JobExecutionListener {

    static final String ANOTHER_RUNNING = "Another GtfsStaticLoadJob is running";

    private final JobRepository jobRepository;
    private final FeedWorkspace workspace;
    private final MeterRegistry meters;

    public GtfsJobListener(JobRepository jobRepository, FeedWorkspace workspace, MeterRegistry meters) {
        this.jobRepository = jobRepository;
        this.workspace = workspace;
        this.meters = meters;
    }

    /** Only the oldest running execution proceeds, so two that start together do not both fail. */
    @Override
    public void beforeJob(JobExecution execution) {
        Optional<JobExecution> older =
                jobRepository.findRunningJobExecutions(PtiJob.GTFS_STATIC_LOAD.jobName()).stream()
                        .filter(e -> e.getId() < execution.getId())
                        .min(Comparator.comparingLong(JobExecution::getId));
        if (older.isPresent()) {
            throw new IllegalStateException(
                    ANOTHER_RUNNING + " (execution " + older.get().getId() + ")");
        }
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.FAILED) {
            workspace.delete(execution.getJobInstance().getInstanceId());
        }
        Counter.builder("pti.gtfs.load")
                .tag("outcome", outcome(execution))
                .register(meters)
                .increment();
    }

    static String outcome(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            return "failed";
        }
        return switch (execution.getExitStatus().getExitCode()) {
            case FetchFeedTasklet.NOOP -> "noop";
            case FetchFeedTasklet.REJECTED -> "rejected";
            default ->
                "true".equals(execution.getExecutionContext().getString(FetchFeedTasklet.REACTIVATING, ""))
                        ? "reactivated"
                        : "activated";
        };
    }
}
