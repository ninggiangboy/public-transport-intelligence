package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.JobRequest;
import java.util.Optional;
import java.util.UUID;

/** Writes {@code ops.job_request} as {@code replay_operator} (DOC-32 E-33, E-35, E-36), always in a transaction. */
public interface JobRequestStore {

    /** The request this actor sent with this {@code Idempotency-Key}. */
    Optional<JobRequest> findByKey(String requestedBy, String idempotencyKey);

    /**
     * Inserts the request.
     *
     * @return the stored row, or empty when the actor already has a request with the same key (nothing is written)
     */
    Optional<JobRequest> insert(JobRequest request);

    Optional<JobRequest> find(UUID id);
}
