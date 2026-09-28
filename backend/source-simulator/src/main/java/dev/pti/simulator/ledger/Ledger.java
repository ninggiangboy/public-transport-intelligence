package dev.pti.simulator.ledger;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Records acknowledged messages (DR-28). Called from the Kafka producer's I/O thread; may block (backpressure). */
public interface Ledger {

    void record(LedgerEntry entry, String topic, int partition, long offset);

    /** Rows waiting to be written. */
    int queueDepth();

    /** Real time of the last successful flush; {@code null} before the first. */
    @Nullable
    Instant lastFlushAt();
}
