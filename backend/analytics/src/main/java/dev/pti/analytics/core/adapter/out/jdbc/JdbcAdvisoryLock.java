package dev.pti.analytics.core.adapter.out.jdbc;

import dev.pti.analytics.core.application.port.AdvisoryLock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link AdvisoryLock} with PostgreSQL's transaction-level advisory locks, keyed by the 64-bit hash of the lock name
 * (DOC-23 §2.5). Several {@code etl-stream} pods can run the same analytics: the lock and the cursor live in the
 * database, so no ShedLock is needed.
 */
public class JdbcAdvisoryLock implements AdvisoryLock {

    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))";

    // pg_advisory_xact_lock returns void; selecting from it keeps the statement a plain query.
    private static final String LOCK = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))) AS l";

    private static final String SET_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', :timeout, true)";

    private final JdbcClient jdbc;

    public JdbcAdvisoryLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(String lockName) {
        requireTransaction();
        return Boolean.TRUE.equals(
                jdbc.sql(TRY_LOCK).param("name", lockName).query(Boolean.class).single());
    }

    @Override
    public void acquire(String lockName, Duration lockTimeout) {
        requireTransaction();
        // SET LOCAL takes no bind parameter, so set_config(..., true), which is transaction-local too.
        jdbc.sql(SET_LOCK_TIMEOUT)
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
        jdbc.sql(LOCK).param("name", lockName).query(Integer.class).single();
    }

    /** Outside a transaction the lock would be released as soon as the statement ends, which protects nothing. */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An advisory lock belongs to a transaction, and none is active");
        }
    }
}
