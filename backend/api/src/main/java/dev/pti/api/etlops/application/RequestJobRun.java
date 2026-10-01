package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.domain.JobCatalog;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.etlops.domain.JobRequestKind;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /etl/jobs} (DOC-32 E-33): asks {@code etl-batch} to run a job. The API only writes the {@code RUN} row
 * of {@code ops.job_request}; {@code JobRequestPoller} starts the job within five seconds (ADR-0013). The job and its
 * parameters are checked against the allow list first, and a repeated {@code Idempotency-Key} gets the first answer.
 */
public final class RequestJobRun {

    private static final Logger log = LoggerFactory.getLogger(RequestJobRun.class);

    private final RequireActiveFeed requireActiveFeed;
    private final JobRequests requests;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final Supplier<UUID> ids;

    public RequestJobRun(
            RequireActiveFeed requireActiveFeed,
            JobRequestStore store,
            TransactionRunner operatorTx,
            WriteMetrics metrics,
            BusinessClock clock,
            Supplier<UUID> ids) {
        this.requireActiveFeed = requireActiveFeed;
        this.requests = new JobRequests(store, operatorTx);
        this.metrics = metrics;
        this.clock = clock;
        this.ids = ids;
    }

    /**
     * @param parameters the parameters as the client sent them: texts, booleans and lists
     * @param idempotencyKey the checked {@code Idempotency-Key}, or {@code null}
     */
    public Submitted<JobRequest> execute(
            Caller caller, String jobName, Map<String, Object> parameters, @Nullable String idempotencyKey) {
        return Measured.write(metrics, "job_run", Measured::of, () -> {
            JobCatalog.Submission job = JobCatalog.validate(
                    jobName,
                    parameters,
                    clock.instant(),
                    () -> requireActiveFeed.execute().timezone());
            Instant now = clock.realNow();
            JobRequest draft = JobRequest.pending(
                    ids.get(),
                    JobRequestKind.RUN,
                    job.jobName(),
                    job.parameters(),
                    null,
                    caller.actor(),
                    idempotencyKey,
                    now);
            Submitted<JobRequest> submitted = requests.submit(draft);
            if (!submitted.idempotent()) {
                log.atInfo()
                        .addKeyValue("requestId", submitted.value().id())
                        .addKeyValue("jobName", jobName)
                        .addKeyValue("actor", draft.requestedBy())
                        .log("job run requested");
            }
            return submitted;
        });
    }
}
