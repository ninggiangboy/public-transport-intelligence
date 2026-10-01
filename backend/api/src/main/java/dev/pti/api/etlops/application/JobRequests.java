package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.domain.IdempotencyKeyReusedException;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.common.tx.TransactionRunner;
import java.util.Optional;

/**
 * Stores a {@link JobRequest} once per {@code Idempotency-Key} (DOC-31 §8): a repeated key with the same ask answers
 * with the stored request and writes nothing, with another ask it is an error, and two requests with the same key at
 * once leave one row and both get it. Shared by the three use cases that write {@code ops.job_request}.
 */
final class JobRequests {

    private final JobRequestStore store;
    private final TransactionRunner tx;

    JobRequests(JobRequestStore store, TransactionRunner tx) {
        this.store = store;
        this.tx = tx;
    }

    /** The answer to a key that was used before, or empty when the draft has no key or the key is new. */
    Optional<Submitted<JobRequest>> repeatOf(JobRequest draft) {
        if (draft.idempotencyKey() == null) {
            return Optional.empty();
        }
        return tx.inTransaction(() -> store.findByKey(draft.requestedBy(), draft.idempotencyKey()))
                .map(prior -> answer(prior, draft));
    }

    Submitted<JobRequest> submit(JobRequest draft) {
        return tx.inTransaction(() -> {
            if (draft.idempotencyKey() != null) {
                Optional<JobRequest> prior = store.findByKey(draft.requestedBy(), draft.idempotencyKey());
                if (prior.isPresent()) {
                    return answer(prior.get(), draft);
                }
            }
            Optional<JobRequest> inserted = store.insert(draft);
            if (inserted.isPresent()) {
                return Submitted.created(inserted.get());
            }
            // Another request with the same key got in between: it is the one that counts.
            JobRequest winner = store.findByKey(draft.requestedBy(), draft.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("A job request vanished after a key conflict"));
            return answer(winner, draft);
        });
    }

    private static Submitted<JobRequest> answer(JobRequest prior, JobRequest draft) {
        if (!prior.sameAsk(draft)) {
            throw new IdempotencyKeyReusedException();
        }
        return Submitted.repeated(prior);
    }
}
