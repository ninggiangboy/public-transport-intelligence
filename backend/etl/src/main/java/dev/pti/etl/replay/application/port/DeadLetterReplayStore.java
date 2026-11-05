package dev.pti.etl.replay.application.port;

import dev.pti.etl.core.domain.InboundMessage;
import java.util.List;
import java.util.UUID;

/** What a replay does to existing dead letters (DOC-22 §3.2 step 4, §4.4): close them and log why. */
public interface DeadLetterReplayStore {

    /** Open dead letters of the same Kafka records as the replayed messages become {@code RESOLVED}. */
    int resolve(List<InboundMessage> written, UUID batchId);

    /** DLQ replay succeeded: {@code REPLAY_REQUESTED → REPLAYED}; false when the row was no longer waiting. */
    boolean markReplayed(UUID deadLetterId, UUID replayRequestId, UUID batchId, int rowsWritten);
}
