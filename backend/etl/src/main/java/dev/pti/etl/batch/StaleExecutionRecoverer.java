package dev.pti.etl.batch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recovers executions left {@code STARTED} by a pod that died (DOC-19 §7.2, DR-24): once neither the execution nor
 * any of its steps has been updated for {@code staleAfter}, mark it {@code FAILED} with exit code {@code STALE} and,
 * for restartable jobs, restart it from the last committed chunk. A zombie that wakes up afterwards fails its next
 * commit on the {@code VERSION} check, so it never writes past the restart point.
 */
public class StaleExecutionRecoverer {

    private static final Logger log = LoggerFactory.getLogger(StaleExecutionRecoverer.class);

    public static final String STALE = "STALE";

    /** Context key {@link JobOperator#recover} puts on the steps and the job it ended. */
    static final String RECOVERED = "batch.recovered";

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final PtiJobLauncher launcher;
    private final JobRequests requests;
    private final JdbcTemplate jdbc;
    private final Duration staleAfter;
    private final Clock batchClock;
    private final MeterRegistry meters;

    /**
     * @param batchClock the clock Spring Batch stamps its {@code LocalDateTime} columns with: the JVM default zone,
     *     UTC in every deployment (DOC-19 §3.1)
     */
    public StaleExecutionRecoverer(
            JobRepository jobRepository,
            JobOperator jobOperator,
            PtiJobLauncher launcher,
            JobRequests requests,
            JdbcTemplate jdbc,
            Duration staleAfter,
            Clock batchClock,
            MeterRegistry meters) {
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.launcher = launcher;
        this.requests = requests;
        this.jdbc = jdbc;
        this.staleAfter = staleAfter;
        this.batchClock = batchClock;
        this.meters = meters;
    }

    /** @return the executions that were recovered */
    public List<JobExecution> recover() {
        LocalDateTime now = LocalDateTime.now(batchClock);
        List<JobExecution> recovered = new ArrayList<>();
        for (String jobName : jobRepository.getJobNames()) {
            for (JobExecution execution : jobRepository.findRunningJobExecutions(jobName)) {
                LocalDateTime lastUpdated = lastUpdated(execution);
                if (lastUpdated != null && Duration.between(lastUpdated, now).compareTo(staleAfter) < 0) {
                    continue;
                }
                try {
                    recovered.add(recover(jobName, execution));
                } catch (RuntimeException e) {
                    log.warn("Cannot recover stale execution {} of {}: {}", execution.getId(), jobName, e.toString());
                }
            }
        }
        return recovered;
    }

    private JobExecution recover(String jobName, JobExecution stale) {
        log.warn(
                "Execution {} of {} has not been updated since {}; recovering it",
                stale.getId(),
                jobName,
                lastUpdated(stale));
        JobExecution failed = jobOperator.recover(stale);
        ExitStatus exit = new ExitStatus(STALE, "Execution became stale: no update for " + staleAfter);
        for (StepExecution step : failed.getStepExecutions()) {
            if (step.getExecutionContext().containsKey(RECOVERED)) {
                step.setExitStatus(exit);
                jobRepository.update(step);
            }
        }
        failed.setExitStatus(exit);
        jobRepository.update(failed);
        Counter.builder("pti.batch.stale.recovered")
                .tag("job", jobName)
                .register(meters)
                .increment();

        Optional<PtiJob> job = PtiJob.byName(jobName);
        PtiJob.Stale policy = job.map(PtiJob::stale).orElse(PtiJob.Stale.FAIL);
        requests.finish(failed);
        switch (policy) {
            case RESTART -> restart(jobName, failed, job.orElseThrow());
            case FAIL_REPLAY_REQUEST -> failReplayRequest(failed);
            case FAIL -> log.info("Execution {} of {} is FAILED; the next slot runs it again", failed.getId(), jobName);
            default -> throw new IllegalStateException("Unexpected stale policy " + policy);
        }
        return failed;
    }

    private void restart(String jobName, JobExecution failed, PtiJob job) {
        if (launcher.job(job).filter(j -> j.isRestartable()).isEmpty()) {
            return;
        }
        try {
            JobExecution restarted = jobOperator.restart(failed);
            log.info("Restarted stale execution {} of {} as {}", failed.getId(), jobName, restarted.getId());
        } catch (Exception e) {
            log.warn("Cannot restart stale execution {} of {}: {}", failed.getId(), jobName, e.toString());
        }
    }

    private void failReplayRequest(JobExecution failed) {
        jdbc.update("""
                UPDATE ops.replay_request SET status = 'FAILED', finished_at = now(), message = 'Execution became stale'
                WHERE job_execution_id = ? AND status = 'RUNNING'
                """, failed.getId());
    }

    private static @Nullable LocalDateTime lastUpdated(JobExecution execution) {
        LocalDateTime updated = execution.getLastUpdated();
        LocalDateTime last = updated != null ? updated : execution.getCreateTime();
        for (StepExecution step : execution.getStepExecutions()) {
            LocalDateTime stepUpdated = step.getLastUpdated();
            if (stepUpdated != null && (last == null || stepUpdated.isAfter(last))) {
                last = stepUpdated;
            }
        }
        return last;
    }
}
