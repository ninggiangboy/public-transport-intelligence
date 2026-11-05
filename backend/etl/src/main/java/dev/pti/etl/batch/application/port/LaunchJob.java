package dev.pti.etl.batch.application.port;

import dev.pti.etl.batch.application.JobLaunch;
import dev.pti.etl.batch.domain.PtiJob;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Starts, restarts and stops the jobs of the catalog (DOC-19 §3.3). A second trigger for the same instance is
 * expected (two pods, a cron that fires twice) and is not an error: DR-24 layer 2. Spring Batch types stay in the
 * adapter that implements this port.
 */
public interface LaunchJob {

    /** True when the job is deployed in this application. */
    boolean isDeployed(PtiJob job);

    /** Starts the job unless an execution of the same instance is already running or already complete. */
    void launchIfNew(PtiJob job, JobLaunch launch);

    /** Starts the job; the outcome tells what happened, it never throws. */
    Start start(PtiJob job, JobLaunch launch);

    /** Restarts a FAILED or STOPPED execution; the outcome tells what happened. */
    Start restart(long jobExecutionId);

    /** Stops a running execution. @return the reason it did not stop, or {@code null} when it did */
    @Nullable
    String stop(long jobExecutionId);

    /** The state of an execution, or empty when there is no such execution. */
    Optional<Execution> execution(long jobExecutionId);

    /** Started: the id of the new execution. Refused: the reason, verbatim. Requeued: the executor had no room. */
    record Start(@Nullable Long executionId, @Nullable String refusal, boolean requeued) {}

    /** The parts of a job execution a decision needs (DOC-19 §7.3). */
    record Execution(
            long id,
            String jobName,
            String status,
            boolean running,
            boolean restartable,
            String exitCode,
            @Nullable String exitDescription,
            @Nullable String requestId) {}
}
