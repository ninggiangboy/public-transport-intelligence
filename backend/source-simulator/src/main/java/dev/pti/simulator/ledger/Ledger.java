package dev.pti.simulator.ledger;

/** Records acknowledged messages (DR-28). Called from the Kafka producer's I/O thread; may block (backpressure). */
public interface Ledger {

    void record(LedgerEntry entry, int partition, long offset);

    /** Rows waiting to be written. */
    int queueDepth();
}
