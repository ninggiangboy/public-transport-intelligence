package dev.pti.api.etlops.domain;

import dev.pti.common.error.PtiException;

/**
 * Raised by the replay store when the database refuses a second replay for the same source or the same record
 * ({@code replay_request_one_raw_per_source}, {@code replay_request_one_per_record}). The transaction that hit it is
 * aborted, so the use case looks the existing replay up in a new one and answers with
 * {@link ReplayAlreadyRunningException}.
 */
public class ActiveReplayConflict extends PtiException {

    private static final long serialVersionUID = 1L;

    public ActiveReplayConflict(Throwable cause) {
        super("A replay is already waiting or running", cause, false);
    }
}
