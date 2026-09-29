package dev.pti.etl.write;

/**
 * Writes dead letters in the caller's transaction (DOC-19 §4.5): a chunk that rolls back leaves none behind.
 * Idempotent on the Kafka position (DOC-22 §1.3).
 */
public interface DeadLetterWriter {

    /** Live streaming: a redelivered record keeps its first dead letter. */
    DeadLetterResult write(DeadLetter letter);

    /** Replay: the existing dead letter of the same Kafka record is refreshed with the latest failure. */
    DeadLetterResult writeReplay(DeadLetter letter);

    enum DeadLetterResult {
        INSERTED,
        UPDATED,
        IGNORED
    }
}
