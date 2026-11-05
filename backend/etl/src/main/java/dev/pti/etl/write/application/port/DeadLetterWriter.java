package dev.pti.etl.write.application.port;

import dev.pti.etl.core.domain.InboundMessage;
import dev.pti.etl.write.domain.DeadLetter;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Writes dead letters in the caller's transaction (DOC-19 §4.5): a chunk that rolls back leaves none behind.
 * Idempotent on the Kafka position (DOC-22 §1.3).
 */
public interface DeadLetterWriter {

    /** Live streaming: a redelivered record keeps its first dead letter. */
    DeadLetterResult write(DeadLetter letter);

    /** Replay: the existing dead letter of the same Kafka record is refreshed with the latest failure. */
    DeadLetterResult writeReplay(DeadLetter letter);

    /**
     * A row the database refused (SQLState 22xxx or 23xxx) while writing one message on its own (DOC-22 §1.1). The
     * implementation reads the SQL state, so the caller never touches {@code java.sql}.
     */
    DeadLetter loadFailure(InboundMessage message, Throwable error, @Nullable String businessKey, UUID batchId);

    enum DeadLetterResult {
        INSERTED,
        UPDATED,
        IGNORED
    }
}
