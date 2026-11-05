package dev.pti.etl.replay.application.port;

import dev.pti.etl.replay.domain.ReplayRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code ops.replay_request} as {@code etl-batch} executes it (DOC-22 §6, ADR-0013). */
public interface ReplayRequestStore {

    /** The oldest PENDING request; {@code SKIP LOCKED} keeps two pods from claiming the same one. */
    Optional<ReplayRequest> claim();

    /** The execution now running the request: first start and restarts alike (DOC-22 §4.7). */
    void running(UUID id, long jobExecutionId);

    void finish(UUID id, long jobExecutionId, boolean done, String statsJson, @Nullable String message);

    void fail(UUID id, String message);

    /** Requests claimed by a pod that died before the job started. */
    int failInterrupted(Duration olderThan);
}
