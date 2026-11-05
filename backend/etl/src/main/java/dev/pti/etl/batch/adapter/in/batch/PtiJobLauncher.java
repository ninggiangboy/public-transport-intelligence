package dev.pti.etl.batch.adapter.in.batch;

import dev.pti.etl.batch.application.JobLaunch;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.domain.PtiJob;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link LaunchJob} on Spring Batch's {@link JobOperator} (DOC-19 §3.3). A second trigger for the same instance is
 * expected (two pods, a cron that fires twice) and is not an error: DR-24 layer 2.
 */
public class PtiJobLauncher implements LaunchJob {

    private static final Logger log = LoggerFactory.getLogger(PtiJobLauncher.class);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<PtiJob, Job> jobs = new EnumMap<>(PtiJob.class);

    public PtiJobLauncher(JobOperator jobOperator, JobRepository jobRepository, List<Job> jobs) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        for (Job job : jobs) {
            PtiJob.byName(job.getName()).ifPresent(p -> this.jobs.put(p, job));
        }
    }

    public Optional<Job> job(PtiJob job) {
        return Optional.ofNullable(jobs.get(job));
    }

    @Override
    public boolean isDeployed(PtiJob job) {
        return jobs.containsKey(job);
    }

    @Override
    public void launchIfNew(PtiJob job, JobLaunch launch) {
        launchIfNew(job, JobParams.of(job, launch));
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

    @Override
    public Start start(PtiJob job, JobLaunch launch) {
        Job bean = jobs.get(job);
        if (bean == null) {
            return new Start(null, job.jobName() + " is not deployed in this application", false);
        }
        JobParameters parameters = JobParams.of(job, launch);
        try {
            JobExecution execution = jobOperator.start(bean, parameters);
            log.info("Started {} execution {} {}", job.jobName(), execution.getId(), parameters);
            return new Start(execution.getId(), null, false);
        } catch (JobExecutionAlreadyRunningException | DuplicateKeyException e) {
            return new Start(null, "An execution of this instance is already running", false);
        } catch (JobInstanceAlreadyCompleteException e) {
            return new Start(null, "This job instance has already completed", false);
        } catch (JobRestartException | InvalidJobParametersException e) {
            return new Start(null, e.getMessage(), false);
        } catch (TaskRejectedException e) {
            return new Start(null, null, true);
        }
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

    @Override
    public Start restart(long jobExecutionId) {
        JobExecution target = jobRepository.getJobExecution(jobExecutionId);
        try {
            JobExecution execution = jobOperator.restart(target);
            log.info("Restarted execution {} as {}", jobExecutionId, execution.getId());
            return new Start(execution.getId(), null, false);
        } catch (JobRestartException e) {
            return new Start(null, e.getMessage(), false);
        } catch (TaskRejectedException e) {
            return new Start(null, null, true);
        }
    }

    @Override
    public String stop(long jobExecutionId) {
        JobExecution target = jobRepository.getJobExecution(jobExecutionId);
        try {
            jobOperator.stop(target);
            return null;
        } catch (JobExecutionNotRunningException e) {
            return "Execution " + jobExecutionId + " is not running";
        }
    }

    @Override
    public Optional<Execution> execution(long jobExecutionId) {
        JobExecution execution = jobRepository.getJobExecution(jobExecutionId);
        if (execution == null) {
            return Optional.empty();
        }
        String jobName = execution.getJobInstance().getJobName();
        boolean restartable = PtiJob.byName(jobName)
                .flatMap(this::job)
                .filter(Job::isRestartable)
                .isPresent();
        ExitStatus exit = execution.getExitStatus();
        return Optional.of(new Execution(
                execution.getId(),
                jobName,
                execution.getStatus().name(),
                execution.isRunning(),
                restartable,
                exit.getExitCode(),
                exit.getExitDescription(),
                execution.getJobParameters().getString(JobLaunch.JOB_REQUEST_ID)));
    }
}
