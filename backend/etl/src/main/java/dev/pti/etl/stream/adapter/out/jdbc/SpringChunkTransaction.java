package dev.pti.etl.stream.adapter.out.jdbc;

import dev.pti.etl.stream.application.port.ChunkTransaction;
import java.util.function.Function;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ChunkTransaction} on Spring's {@link TransactionTemplate} (DOC-49 §5.1): one transaction per chunk, and the
 * savepoints the item-by-item scan rolls back to before dead-lettering a refused record.
 */
public final class SpringChunkTransaction implements ChunkTransaction {

    private final TransactionTemplate template;

    public SpringChunkTransaction(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Function<Savepoints, T> work) {
        return template.execute(status -> work.apply(new SpringSavepoints(status)));
    }

    private record SpringSavepoints(TransactionStatus status) implements Savepoints {

        @Override
        public Savepoint create() {
            return new SpringSavepoint(status, status.createSavepoint());
        }
    }

    private record SpringSavepoint(TransactionStatus status, Object savepoint) implements Savepoint {

        @Override
        public void release() {
            status.releaseSavepoint(savepoint);
        }

        @Override
        public void rollback() {
            status.rollbackToSavepoint(savepoint);
        }
    }
}
