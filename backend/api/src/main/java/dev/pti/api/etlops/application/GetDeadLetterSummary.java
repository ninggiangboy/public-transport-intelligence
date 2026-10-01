package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.domain.DeadLetterSummary;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/dlq/summary} (DOC-32 E-41): how many dead letters are open, by status, source and severity. */
public final class GetDeadLetterSummary {

    private final DeadLetterReader letters;
    private final BusinessClock clock;
    private final TransactionRunner tx;

    public GetDeadLetterSummary(DeadLetterReader letters, BusinessClock clock, TransactionRunner tx) {
        this.letters = letters;
        this.clock = clock;
        this.tx = tx;
    }

    public DeadLetterSummary execute() {
        return tx.inTransaction(() -> letters.summary(clock.realNow()));
    }
}
