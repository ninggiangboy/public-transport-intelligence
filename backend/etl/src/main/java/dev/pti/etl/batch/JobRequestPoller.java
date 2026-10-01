package dev.pti.etl.batch;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.JobRequests.JobRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Executes {@code ops.job_request} rows (DOC-19 §7.3): claims one, calls {@link JobOperator}, and links the request to
 * the execution; {@link JobRequestListener} records the end. Spring Batch refuses to create an execution inside a
 * caller's transaction, so the claim commits first and a request whose launch never happened is failed after
 * {@link #INTERRUPTED_AFTER}.
 */
public class JobRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(JobRequestPoller.class);

    static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(2);

    /** At most this many requests per tick, so one tick never holds its lock for long. */
    static final int MAX_CLAIMS = 10;

    private final JobRequests requests;
    private final PtiJobLauncher launcher;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final BusinessClock clock;
    private final BooleanSupplier executorHasRoom;
    private final MeterRegistry meters;
    private final List<JobParameterCheck> parameterChecks;

    public JobRequestPoller(
            JobRequests requests,
            PtiJobLauncher launcher,
            JobOperator jobOperator,
            JobRepository jobRepository,
            BusinessClock clock,
            BooleanSupplier executorHasRoom,
            MeterRegistry meters,
            List<JobParameterCheck> parameterChecks) {
        this.parameterChecks = List.copyOf(parameterChecks);
        this.requests = requests;
        this.launcher = launcher;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.executorHasRoom = executorHasRoom;
        this.meters = meters;
    }

    public void poll() {
        int interrupted = requests.failInterrupted(INTERRUPTED_AFTER);
        if (interrupted > 0) {
            log.warn("Failed {} job request(s) whose launch was interrupted", interrupted);
        }
        for (int i = 0; i < MAX_CLAIMS && executorHasRoom.getAsBoolean(); i++) {
            Optional<JobRequest> claimed = requests.claim();
            if (claimed.isEmpty()) {
                return;
            }
            handle(claimed.get());
        }
    }

    void handle(JobRequest request) {
        String outcome;
        try {
            outcome = switch (request.kind()) {
                case RUN -> run(request);
                case RESTART -> restart(request);
                case STOP -> stop(request);
            };
        } catch (TaskRejectedException e) {
            requests.release(request.id());
            outcome = "requeued";
        } catch (Rejected e) {
            log.info("Rejected job request {} ({} {}): {}", request.id(), request.kind(), request.jobName(), e.reason);
            requests.reject(request.id(), e.reason);
            outcome = "rejected";
        } catch (RuntimeException e) {
            log.error("Job request {} failed to start", request.id(), e);
            requests.reject(request.id(), "Could not start: " + e.getMessage());
            outcome = "error";
        }
        Counter.builder("pti.batch.job.requests")
                .tag("kind", request.kind().name())
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private String run(JobRequest request) {
        PtiJob job = PtiJob.byName(request.jobName())
                .filter(PtiJob::manualRun)
                .filter(j -> launcher.job(j).isPresent())
                .orElseThrow(() -> new Rejected("Unknown job " + request.jobName()));
        JobParametersBuilder parameters = parameters(job, request);
        try {
            JobExecution execution = launcher.start(job, parameters.toJobParameters());
            linkAndCheck(request, execution);
            return "started";
        } catch (JobExecutionAlreadyRunningException | DuplicateKeyException e) {
            throw new Rejected("An execution of this instance is already running");
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new Rejected("This job instance has already completed");
        } catch (JobRestartException | InvalidJobParametersException e) {
            throw new Rejected(e.getMessage());
        }
    }

    /** Checks the parameters against the job's allow list and fills in the identifying one (DOC-19 §2). */
    JobParametersBuilder parameters(PtiJob job, JobRequest request) {
        String identity = job.identity().parameter();
        JobParametersBuilder builder = new JobParametersBuilder();
        for (Map.Entry<String, String> p : request.parameters().entrySet()) {
            boolean identifying = p.getKey().equals(identity) && job.identity() != PtiJob.Identity.RUN_KEY;
            if (!identifying && !job.extraParameters().contains(p.getKey())) {
                throw new Rejected("Parameter " + p.getKey() + " is not allowed for " + job.jobName());
            }
            builder.addString(p.getKey(), validated(job, p.getKey(), p.getValue()), identifying);
        }
        validatedAsAWhole(job, request.parameters());
        if (!request.parameters().containsKey(identity)) {
            builder.addString(identity, defaultIdentity(job, request), true);
        }
        return builder.addString(JobParams.JOB_REQUEST_ID, request.id().toString(), false);
    }

    /** The checks that look at several parameters at once, after every one of them is valid on its own. */
    private void validatedAsAWhole(PtiJob job, Map<String, String> parameters) {
        for (JobParameterCheck check : parameterChecks) {
            try {
                check.checkAll(job, parameters);
            } catch (IllegalArgumentException e) {
                throw new Rejected(e.getMessage());
            }
        }
    }

    private String validated(PtiJob job, String key, String value) {
        for (JobParameterCheck check : parameterChecks) {
            try {
                check.check(job, key, value);
            } catch (IllegalArgumentException e) {
                throw new Rejected(e.getMessage());
            }
        }
        if (!key.equals(job.identity().parameter())) {
            return value;
        }
        try {
            return switch (job.identity()) {
                case RUN_DATE -> LocalDate.parse(value).toString();
                case SLOT -> Instant.parse(value).toString();
                default -> value;
            };
        } catch (DateTimeParseException e) {
            throw new Rejected("Parameter " + key + " is not a valid "
                    + job.identity().name().toLowerCase(Locale.ROOT) + ": " + value);
        }
    }

    private String defaultIdentity(PtiJob job, JobRequest request) {
        return switch (job.identity()) {
            case RUN_KEY -> "manual:" + request.id();
            case RUN_DATE ->
                LocalDate.ofInstant(clock.realNow(), ZoneOffset.UTC).toString();
            case SLOT -> "manual:" + request.id();
            case REPLAY_REQUEST -> throw new Rejected(job.jobName() + " starts from a replay request only");
        };
    }

    private String restart(JobRequest request) {
        JobExecution target = target(request);
        if (target.getStatus() != BatchStatus.FAILED && target.getStatus() != BatchStatus.STOPPED) {
            throw new Rejected("Execution " + target.getId() + " is " + target.getStatus() + ", not FAILED or STOPPED");
        }
        Optional<PtiJob> job = PtiJob.byName(target.getJobInstance().getJobName());
        Optional<Job> bean = job.flatMap(launcher::job);
        if (bean.isEmpty() || !bean.get().isRestartable()) {
            throw new Rejected("Job " + target.getJobInstance().getJobName() + " cannot be restarted");
        }
        try {
            JobExecution execution = jobOperator.restart(target);
            linkAndCheck(request, execution);
            return "started";
        } catch (JobRestartException e) {
            throw new Rejected(e.getMessage());
        }
    }

    private String stop(JobRequest request) {
        JobExecution target = target(request);
        try {
            jobOperator.stop(target);
        } catch (JobExecutionNotRunningException e) {
            throw new Rejected("Execution " + target.getId() + " is not running");
        }
        requests.done(request.id(), target.getId(), "Stop requested");
        return "stopped";
    }

    private JobExecution target(JobRequest request) {
        Long id = request.targetExecutionId();
        JobExecution target = id == null ? null : jobRepository.getJobExecution(id);
        if (target == null) {
            throw new Rejected("No job execution " + id);
        }
        return target;
    }

    /** Links the request; a job that already ended before the link was written is finished here instead. */
    private void linkAndCheck(JobRequest request, JobExecution execution) {
        requests.started(request.id(), execution.getId());
        JobExecution current = jobRepository.getJobExecution(execution.getId());
        if (current != null && !current.isRunning()) {
            requests.finish(current);
        }
    }

    /** A request that cannot run; its reason goes to {@code job_request.message}. */
    static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        Rejected(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
