package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunDetail;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.List;
import java.util.Objects;

/**
 * {@code GET /etl/jobs/{runId}} (DOC-32 E-32): one run in full. A batch job comes with its parameters, steps and the
 * request that started it; a stream run with its micro-batches. An id of the wrong shape and an id that does not
 * exist are the same 404.
 */
public final class GetJobRun {

    /** The most micro-batches of a stream run the answer lists (DOC-32 E-32). */
    static final int MAX_BATCHES = 120;

    private final JobRunReader runs;
    private final TransactionRunner tx;

    public GetJobRun(JobRunReader runs, TransactionRunner tx) {
        this.runs = runs;
        this.tx = tx;
    }

    public JobRunDetail execute(String runId) {
        RunId id = RunId.parse(runId).orElseThrow(GetJobRun::notFound);
        return tx.inTransaction(() -> {
            JobRun run = runs.find(id).orElseThrow(GetJobRun::notFound).withRestartability();
            if (id.kind() == RunKind.STREAM) {
                return new JobRunDetail(
                        run, List.of(), List.of(), null, runs.streamBatches(id.listenerId(), id.minute(), MAX_BATCHES));
            }
            long execution = Objects.requireNonNull(id.jobExecutionId());
            return new JobRunDetail(
                    run,
                    runs.parameters(execution),
                    runs.steps(execution),
                    runs.requestOf(execution).orElse(null),
                    List.of());
        });
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The run does not exist.");
    }
}
