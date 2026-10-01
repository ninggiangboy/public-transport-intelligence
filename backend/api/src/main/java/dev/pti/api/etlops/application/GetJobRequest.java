package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRequestReader;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/** {@code GET /etl/job-requests/{id}} (DOC-32 E-34): where a request of a job run, restart or stop has got to. */
public final class GetJobRequest {

    private final JobRequestReader requests;
    private final TransactionRunner tx;

    public GetJobRequest(JobRequestReader requests, TransactionRunner tx) {
        this.requests = requests;
        this.tx = tx;
    }

    public JobRequest execute(UUID id) {
        return tx.inTransaction(() -> requests.find(id))
                .orElseThrow(() -> new NotFoundException("The job request does not exist."));
    }
}
