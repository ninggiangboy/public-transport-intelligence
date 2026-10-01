package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A replay for the same source or record is already waiting or running (409 {@code replay-already-running}). */
public class ReplayAlreadyRunningException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final transient @Nullable UUID existingReplayId;

    public ReplayAlreadyRunningException(String detail, @Nullable UUID existingReplayId) {
        super(detail);
        this.existingReplayId = existingReplayId;
    }

    @Override
    public ProblemType type() {
        return ProblemType.REPLAY_ALREADY_RUNNING;
    }

    @Override
    public Map<String, Object> extensions() {
        return existingReplayId == null ? Map.of() : Map.of("existingReplayId", existingReplayId.toString());
    }
}
