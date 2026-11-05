package dev.pti.etl.batch.application;

import dev.pti.common.error.PtiException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.application.port.BatchRequestMetrics;
import dev.pti.etl.batch.application.port.JobParameterCheck;
import dev.pti.etl.batch.application.port.JobRequestStore;
import dev.pti.etl.batch.application.port.JobRequestStore.FinishedJob;
import dev.pti.etl.batch.application.port.JobRequestStore.JobRequest;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.domain.PtiJob;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the {@code ops.job_request} rows (DOC-19 §7.3): claims one, starts the job through {@link LaunchJob}, and
 * links the request to the execution; the job listener records the end. Spring Batch refuses to create an execution
 * inside a caller's transaction, so the claim commits first and a request whose launch never happened is failed after
 * {@link #INTERRUPTED_AFTER}.
 */
public final class HandleJobRequests {

    private static final Logger log = LoggerFactory.getLogger(HandleJobRequests.class);

    public static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(2);

    /** At most this many requests per tick, so one tick never holds its lock for long. */
    static final int MAX_CLAIMS = 10;

    private final JobRequestStore requests;
    private final LaunchJob launcher;
    private final BusinessClock clock;
    private final BooleanSupplier executorHasRoom;
    private final BatchRequestMetrics metrics;
    private final List<JobParameterCheck> parameterChecks;

    public HandleJobRequests(
            JobRequestStore requests,
            LaunchJob launcher,
            BusinessClock clock,
            BooleanSupplier executorHasRoom,
            BatchRequestMetrics metrics,
            List<JobParameterCheck> parameterChecks) {
        this.requests = requests;
        this.launcher = launcher;
        this.clock = clock;
        this.executorHasRoom = executorHasRoom;
        this.metrics = metrics;
        this.parameterChecks = List.copyOf(parameterChecks);
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

    public void handle(JobRequest request) {
        String outcome;
        try {
            outcome = switch (request.kind()) {
                case RUN -> run(request);
                case RESTART -> restart(request);
                case STOP -> stop(request);
            };
        } catch (Requeued e) {
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
        metrics.requestHandled(request.kind(), outcome);
    }

    private String run(JobRequest request) {
        PtiJob job = PtiJob.byName(request.jobName())
                .filter(PtiJob::manualRun)
                .filter(launcher::isDeployed)
                .orElseThrow(() -> new Rejected("Unknown job " + request.jobName()));
        LaunchJob.Start start = launcher.start(job, parameters(job, request));
        if (start.requeued()) {
            throw new Requeued();
        }
        if (start.refusal() != null) {
            throw new Rejected(start.refusal());
        }
        linkAndCheck(request, start.executionId());
        return "started";
    }

    /** Checks the parameters against the job's allow list and fills in the identifying one (DOC-19 §2). */
    public JobLaunch parameters(PtiJob job, JobRequest request) {
        String identityName = job.identity().parameter();
        Map<String, String> extras = new LinkedHashMap<>();
        String identity = null;
        String refused = null;
        for (Map.Entry<String, String> p : request.parameters().entrySet()) {
            boolean identifying = p.getKey().equals(identityName) && job.identity() != PtiJob.Identity.RUN_KEY;
            if (!identifying && !job.extraParameters().contains(p.getKey())) {
                refused = "Parameter " + p.getKey() + " is not allowed for " + job.jobName();
                break;
            }
            String value = validated(job, p.getKey(), p.getValue());
            if (p.getKey().equals(identityName)) {
                identity = value;
            } else {
                extras.put(p.getKey(), value);
            }
        }
        if (refused == null) {
            refused = refusedAsAWhole(job, request.parameters());
        }
        if (refused != null) {
            throw new Rejected(refused);
        }
        if (identity == null) {
            identity = defaultIdentity(job, request);
        }
        extras.put(JobLaunch.JOB_REQUEST_ID, request.id().toString());
        return new JobLaunch(identity, extras);
    }

    /**
     * The checks that look at several parameters at once, after every one of them is valid on its own; the reason
     * the request is refused, or {@code null}.
     */
    private @Nullable String refusedAsAWhole(PtiJob job, Map<String, String> parameters) {
        for (JobParameterCheck check : parameterChecks) {
            try {
                check.checkAll(job, parameters);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
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
        LaunchJob.Execution target = target(request);
        if (!target.status().equals("FAILED") && !target.status().equals("STOPPED")) {
            throw new Rejected("Execution " + target.id() + " is " + target.status() + ", not FAILED or STOPPED");
        }
        if (!target.restartable()) {
            throw new Rejected("Job " + target.jobName() + " cannot be restarted");
        }
        LaunchJob.Start start = launcher.restart(target.id());
        if (start.requeued()) {
            throw new Requeued();
        }
        if (start.refusal() != null) {
            throw new Rejected(start.refusal());
        }
        linkAndCheck(request, start.executionId());
        return "started";
    }

    private String stop(JobRequest request) {
        LaunchJob.Execution target = target(request);
        String refusal = launcher.stop(target.id());
        if (refusal != null) {
            throw new Rejected(refusal);
        }
        requests.done(request.id(), target.id(), "Stop requested");
        return "stopped";
    }

    private LaunchJob.Execution target(JobRequest request) {
        Long id = request.targetExecutionId();
        return (id == null ? Optional.<LaunchJob.Execution>empty() : launcher.execution(id))
                .orElseThrow(() -> new Rejected("No job execution " + id));
    }

    /** Links the request; a job that already ended before the link was written is finished here instead. */
    private void linkAndCheck(JobRequest request, @Nullable Long executionId) {
        requests.started(request.id(), executionId);
        launcher.execution(executionId)
                .filter(execution -> !execution.running())
                .ifPresent(execution -> requests.finish(finished(execution)));
    }

    private static FinishedJob finished(LaunchJob.Execution execution) {
        return new FinishedJob(
                "COMPLETED".equals(execution.status()),
                execution.exitCode(),
                execution.exitDescription(),
                execution.id(),
                execution.requestId());
    }

    /** A request that cannot run; its reason goes to {@code job_request.message}. */
    static final class Rejected extends PtiException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        Rejected(String reason) {
            super(reason, null, false);
            this.reason = reason;
        }
    }

    /** The executor had no room: put the request back for the next tick. */
    private static final class Requeued extends PtiException {

        private static final long serialVersionUID = 1L;

        Requeued() {
            super("The job executor had no room", null, false);
        }
    }
}
