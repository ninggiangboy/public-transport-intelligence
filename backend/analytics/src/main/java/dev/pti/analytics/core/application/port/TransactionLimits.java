package dev.pti.analytics.core.application.port;

import java.time.Duration;

/** Time limits of the current transaction (DOC-23 §12.1). They end with the transaction. */
public interface TransactionLimits {

    /** {@code SET LOCAL statement_timeout}: every statement of the transaction may run this long. */
    void statementTimeout(Duration timeout);
}
