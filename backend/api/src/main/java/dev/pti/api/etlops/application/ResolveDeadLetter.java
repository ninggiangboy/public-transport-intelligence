package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/**
 * {@code POST /etl/dlq/{id}/resolve} (DOC-32 E-47): marks a {@code MANUAL} dead letter as handled outside the system,
 * with a note that goes to the action log. The write is {@link DeadLetterClosure}.
 */
public final class ResolveDeadLetter {

    private final DeadLetterClosure closure;

    public ResolveDeadLetter(
            DeadLetterStore letters,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        this.closure = new DeadLetterClosure(letters, operatorTx, events, metrics, clock);
    }

    public DeadLetterDetail execute(Caller caller, UUID id, String note) {
        return closure.close(DeadLetterAction.RESOLVE, "resolve", caller, id, "note", note);
    }
}
