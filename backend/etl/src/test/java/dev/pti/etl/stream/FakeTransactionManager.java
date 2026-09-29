package dev.pti.etl.stream;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** A transaction manager without a database that counts commits, rollbacks and savepoint rollbacks. */
final class FakeTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    int commits;
    int rollbacks;
    int savepointRollbacks;

    @Override
    protected Object doGetTransaction() {
        return new Transaction();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks++;
    }

    private final class Transaction implements org.springframework.transaction.SavepointManager {
        @Override
        public Object createSavepoint() {
            return new Object();
        }

        @Override
        public void rollbackToSavepoint(Object savepoint) {
            savepointRollbacks++;
        }

        @Override
        public void releaseSavepoint(Object savepoint) {}
    }
}
