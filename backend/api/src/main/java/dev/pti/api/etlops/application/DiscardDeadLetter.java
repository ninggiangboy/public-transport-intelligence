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
 * {@code POST /etl/dlq/{id}/discard} (DOC-32 E-46): gives up on a {@code NEW}, {@code MANUAL} or {@code PENDING_CONFIRM}
 * dead letter, with a reason that goes to the action log. The write is {@link DeadLetterClosure}.
 */
public final class DiscardDeadLetter {

    private final DeadLetterClosure closure;

    public DiscardDeadLetter(
            DeadLetterStore letters,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        this.closure = new DeadLetterClosure(letters, operatorTx, events, metrics, clock);
    }

    public DeadLetterDetail execute(Caller caller, UUID id, String reason) {
        return closure.close(DeadLetterAction.DISCARD, "discard", caller, id, "reason", reason);
    }
}
