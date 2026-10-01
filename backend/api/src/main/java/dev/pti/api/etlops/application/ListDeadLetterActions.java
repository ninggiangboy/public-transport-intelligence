package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.domain.ActionLogFilter;
import dev.pti.api.etlops.domain.ActionLogItem;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/dlq/actions} (DOC-32 E-48): the log of what was done to dead letters, by people and by automation. */
public final class ListDeadLetterActions {

    private final DeadLetterReader letters;
    private final TransactionRunner tx;

    public ListDeadLetterActions(DeadLetterReader letters, TransactionRunner tx) {
        this.letters = letters;
        this.tx = tx;
    }

    public Page<ActionLogItem> execute(ActionLogFilter filter, PageRequest page) {
        return tx.inTransaction(() -> letters.actions(filter, page));
    }
}
