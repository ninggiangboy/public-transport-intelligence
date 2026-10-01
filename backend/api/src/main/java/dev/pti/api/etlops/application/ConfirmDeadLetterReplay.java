package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /etl/dlq/{id}/confirm} (DOC-32 E-45): confirms the replay of a {@code PENDING_CONFIRM} dead letter. As
 * {@link ReplayDeadLetter}, and the action log also gets {@code CONFIRMED} with the confidence of the classification
 * (FR-09.3).
 */
public final class ConfirmDeadLetterReplay {

    private final DeadLetterReplays replays;

    public ConfirmDeadLetterReplay(
            DeadLetterStore letters,
            ReplayRequestStore store,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock,
            Supplier<UUID> ids) {
        this.replays = new DeadLetterReplays(letters, store, operatorTx, events, metrics, clock, ids);
    }

    public Submitted<ReplayRequest> execute(Caller caller, UUID id, @Nullable String idempotencyKey) {
        return replays.run(DeadLetterAction.CONFIRM, "confirm", caller, id, idempotencyKey);
    }
}
