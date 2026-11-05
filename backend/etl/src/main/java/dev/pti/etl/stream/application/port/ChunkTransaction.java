package dev.pti.etl.stream.application.port;

import java.util.function.Function;

/**
 * The transaction of one chunk (DOC-19 §6.2, DOC-49 §5.1). The template opens it around the write, and the
 * item-by-item scan of a data error uses savepoints inside it. The Spring implementation lives in
 * {@code stream.adapter.out.jdbc}; the template stays plain Java.
 */
public interface ChunkTransaction {

    /** Runs {@code work} in one transaction; an exception rolls the transaction back and propagates unchanged. */
    <T> T inTransaction(Function<Savepoints, T> work);

    /** The savepoints of the transaction the enclosing {@link #inTransaction} opened. */
    interface Savepoints {

        Savepoint create();
    }

    /** One savepoint: released after an item that wrote, rolled back to before dead-lettering a refused one. */
    interface Savepoint {

        void release();

        void rollback();
    }
}
