package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.domain.DeadLetterFilter;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/dlq} (DOC-32 E-40): the dead letters, newest first, with a 200 character preview of the payload. */
public final class ListDeadLetters {

    private final DeadLetterReader letters;
    private final TransactionRunner tx;

    public ListDeadLetters(DeadLetterReader letters, TransactionRunner tx) {
        this.letters = letters;
        this.tx = tx;
    }

    public Page<DeadLetterItem> execute(DeadLetterFilter filter, PageRequest page) {
        return tx.inTransaction(() -> letters.list(filter, page));
    }
}
