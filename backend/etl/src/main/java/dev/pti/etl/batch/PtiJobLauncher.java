package dev.pti.etl.batch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Starts the jobs of {@link PtiJob} on the asynchronous job executor (DOC-19 §3.3). A second trigger for the same
 * instance is expected (two pods, a cron that fires twice) and is not an error: DR-24 layer 2.
 */
public class PtiJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(PtiJobLauncher.class);

    private final JobOperator jobOperator;
    private final Map<PtiJob, Job> jobs = new EnumMap<>(PtiJob.class);

    public PtiJobLauncher(JobOperator jobOperator, List<Job> jobs) {
        this.jobOperator = jobOperator;
        for (Job job : jobs) {
            PtiJob.byName(job.getName()).ifPresent(p -> this.jobs.put(p, job));
        }
    }

    public Optional<Job> job(PtiJob job) {
        return Optional.ofNullable(jobs.get(job));
    }

    /**
     * Starts the job unless the instance is already running or complete.
     *
     * @return the new execution, or empty when there was nothing to do
     */
    public Optional<JobExecution> launchIfNew(PtiJob job, JobParameters parameters) {
        try {
            return Optional.of(start(job, parameters));
        } catch (JobExecutionAlreadyRunningException | DuplicateKeyException e) {
            // DuplicateKeyException: another pod created the same instance at the same moment (READ_COMMITTED).
            log.debug("{} {} is already running", job.jobName(), parameters);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("{} {} has already completed", job.jobName(), parameters);
        } catch (JobRestartException | InvalidJobParametersException e) {
            log.warn("Cannot start {} {}: {}", job.jobName(), parameters, e.getMessage());
        }
        return Optional.empty();
    }

    /** Starts the job; the caller decides what a refusal means. */
    public JobExecution start(PtiJob job, JobParameters parameters)
            throws JobExecutionAlreadyRunningException, JobInstanceAlreadyCompleteException, JobRestartException,
                    InvalidJobParametersException {
        Job bean = jobs.get(job);
        if (bean == null) {
            throw new IllegalStateException(job.jobName() + " is not deployed in this application");
        }
        JobExecution execution = jobOperator.start(bean, parameters);
        log.info("Started {} execution {} {}", job.jobName(), execution.getId(), parameters);
        return execution;
    }
}
