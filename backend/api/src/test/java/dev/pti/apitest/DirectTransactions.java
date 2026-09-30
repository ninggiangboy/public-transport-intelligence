package dev.pti.apitest;

import dev.pti.common.tx.TransactionRunner;
import java.util.function.Supplier;

/** A {@link TransactionRunner} that runs the work as it is: for use cases tested with in-memory ports. */
public final class DirectTransactions implements TransactionRunner {

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    @Override
    public <T> T inNewTransaction(Supplier<T> work) {
        return work.get();
    }
}
