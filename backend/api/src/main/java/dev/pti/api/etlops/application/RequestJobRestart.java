package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobCatalog;
import dev.pti.api.etlops.domain.JobNotRestartableException;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.etlops.domain.JobRequestKind;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /etl/jobs/{runId}/restart} (DOC-32 E-35): asks {@code etl-batch} to restart a failed or stopped job
 * execution. The run is looked up first (a restart of something that does not exist is a 404, of a stream run or a
 * run that is not {@code FAILED} or {@code STOPPED} a 409), then a {@code RESTART} row is written. {@code etl-batch}
 * checks again and may reject the request when the state changed in between.
 */
public final class RequestJobRestart {

    private static final Logger log = LoggerFactory.getLogger(RequestJobRestart.class);

    private final JobRunReader runs;
    private final TransactionRunner readerTx;
    private final JobRequests requests;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final Supplier<UUID> ids;

    public RequestJobRestart(
            JobRunReader runs,
            JobRequestStore store,
            TransactionRunner readerTx,
            TransactionRunner operatorTx,
            WriteMetrics metrics,
            BusinessClock clock,
            Supplier<UUID> ids) {
        this.runs = runs;
        this.readerTx = readerTx;
        this.requests = new JobRequests(store, operatorTx);
        this.metrics = metrics;
        this.clock = clock;
        this.ids = ids;
    }

    public Submitted<JobRequest> execute(Caller caller, String runId, @Nullable String idempotencyKey) {
        return Measured.write(metrics, "job_restart", Measured::of, () -> {
            JobRun run = findRun(runId);
            JobRequest draft = JobRequest.pending(
                    ids.get(),
                    JobRequestKind.RESTART,
                    run.name(),
                    Map.of(),
                    run.jobExecutionId(),
                    caller.actor(),
                    idempotencyKey,
                    clock.realNow());
            Optional<Submitted<JobRequest>> repeat = requests.repeatOf(draft);
            if (repeat.isPresent()) {
                return repeat.get();
            }
            refuseWhatCannotRestart(run);
            Submitted<JobRequest> submitted = requests.submit(draft);
            if (!submitted.idempotent()) {
                log.atInfo()
                        .addKeyValue("requestId", submitted.value().id())
                        .addKeyValue("runId", runId)
                        .addKeyValue("actor", draft.requestedBy())
                        .log("job restart requested");
            }
            return submitted;
        });
    }

    private JobRun findRun(String runId) {
        RunId id = RunId.parse(runId).orElseThrow(() -> new NotFoundException("The run does not exist."));
        return readerTx.inTransaction(() -> runs.find(id))
                .orElseThrow(() -> new NotFoundException("The run does not exist."));
    }

    private static void refuseWhatCannotRestart(JobRun run) {
        if (run.kind() == RunKind.STREAM) {
            throw new JobNotRestartableException("Streaming runs cannot be restarted.");
        }
        if (!run.status().equals("FAILED") && !run.status().equals("STOPPED")) {
            throw new JobNotRestartableException("Only FAILED or STOPPED executions can be restarted.");
        }
        if (!JobCatalog.isRestartable(run.name())) {
            throw new JobNotRestartableException(run.name() + " is not restartable.");
        }
    }
}
