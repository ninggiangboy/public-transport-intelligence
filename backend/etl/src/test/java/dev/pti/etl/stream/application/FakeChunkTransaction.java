package dev.pti.etl.stream.application;

import dev.pti.etl.stream.application.port.ChunkTransaction;
import java.util.function.Function;

/** A transaction without a database that counts commits, rollbacks and savepoint rollbacks. */
final class FakeChunkTransaction implements ChunkTransaction {

    int commits;
    int rollbacks;
    int savepointRollbacks;

    @Override
    public <T> T inTransaction(Function<Savepoints, T> work) {
        try {
            T result = work.apply(new Savepoints() {
                @Override
                public Savepoint create() {
                    return new Savepoint() {
                        @Override
                        public void release() {}

                        @Override
                        public void rollback() {
                            savepointRollbacks++;
                        }
                    };
                }
            });
            commits++;
            return result;
        } catch (RuntimeException e) {
            rollbacks++;
            throw e;
        }
    }
}
