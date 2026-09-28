package dev.pti.simulator.ledger;

/** Drops ledger rows. Stands in until the ledger writer lands (P1-11). */
public final class DiscardingLedger implements Ledger {

    @Override
    public void record(LedgerEntry entry, int partition, long offset) {}

    @Override
    public int queueDepth() {
        return 0;
    }
}
