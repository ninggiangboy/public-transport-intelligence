package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.ActiveReplayConflict;
import dev.pti.api.etlops.domain.ReplayRequest;
import java.util.Optional;
import java.util.UUID;

/** Writes {@code ops.replay_request} as {@code replay_operator} (DOC-32 E-44, E-45, E-50), in a transaction. */
public interface ReplayRequestStore {

    /** The request this actor sent with this {@code Idempotency-Key}. */
    Optional<ReplayRequest> findByKey(String requestedBy, String idempotencyKey);

    /**
     * Inserts the request.
     *
     * @return the stored row, or empty when the actor already has a request with the same key (nothing is written)
     * @throws ActiveReplayConflict when the source (raw range) or the record already has a replay waiting or running;
     *     the transaction is then aborted
     */
    Optional<ReplayRequest> insert(ReplayRequest request);

    Optional<ReplayRequest> find(UUID id);

    /** The raw zone replay of the source that is waiting or running. */
    Optional<UUID> activeRawReplay(String source);

    /** The replay of the dead letter that is waiting or running. */
    Optional<UUID> activeRecordReplay(UUID deadLetterId);
}
