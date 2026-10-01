package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobNotRunningException;
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
 * {@code POST /etl/jobs/{runId}/stop} (DOC-32 E-36): asks {@code etl-batch} to stop a running job execution. Stopping is
 * cooperative: Spring Batch stops at the next chunk boundary, so {@code STOPPED} may show a few seconds later.
 */
public final class RequestJobStop {

    private static final Logger log = LoggerFactory.getLogger(RequestJobStop.class);

    private final JobRunReader runs;
    private final TransactionRunner readerTx;
    private final JobRequests requests;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final Supplier<UUID> ids;

    public RequestJobStop(
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
        return Measured.write(metrics, "job_stop", Measured::of, () -> {
            JobRun run = findRun(runId);
            JobRequest draft = JobRequest.pending(
                    ids.get(),
                    JobRequestKind.STOP,
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
            if (run.kind() == RunKind.STREAM) {
                throw new JobNotRunningException("Streaming runs cannot be stopped.");
            }
            if (!run.status().equals("STARTING") && !run.status().equals("STARTED")) {
                throw new JobNotRunningException("Only STARTING or STARTED executions can be stopped.");
            }
            Submitted<JobRequest> submitted = requests.submit(draft);
            if (!submitted.idempotent()) {
                log.atInfo()
                        .addKeyValue("requestId", submitted.value().id())
                        .addKeyValue("runId", runId)
                        .addKeyValue("actor", draft.requestedBy())
                        .log("job stop requested");
            }
            return submitted;
        });
    }

    private JobRun findRun(String runId) {
        RunId id = RunId.parse(runId).orElseThrow(() -> new NotFoundException("The run does not exist."));
        return readerTx.inTransaction(() -> runs.find(id))
                .orElseThrow(() -> new NotFoundException("The run does not exist."));
    }
}
