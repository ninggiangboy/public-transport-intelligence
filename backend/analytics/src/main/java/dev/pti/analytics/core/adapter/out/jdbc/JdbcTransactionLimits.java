package dev.pti.analytics.core.adapter.out.jdbc;

import dev.pti.analytics.core.application.port.TransactionLimits;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link TransactionLimits} with transaction-local PostgreSQL settings (DOC-23 §12.1). */
public class JdbcTransactionLimits implements TransactionLimits {

    private static final String SET_STATEMENT_TIMEOUT = "SELECT set_config('statement_timeout', :timeout, true)";

    private final JdbcClient jdbc;

    public JdbcTransactionLimits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void statementTimeout(Duration timeout) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A statement timeout is set for a transaction, and none is active");
        }
        jdbc.sql(SET_STATEMENT_TIMEOUT)
                .param("timeout", timeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }
}
