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
 * {@code POST /etl/dlq/{id}/replay} (DOC-32 E-44): replays a {@code NEW} or {@code MANUAL} dead letter, with the edited
 * payload when there is one. The write is {@link DeadLetterReplays}.
 */
public final class ReplayDeadLetter {

    private final DeadLetterReplays replays;

    public ReplayDeadLetter(
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
        return replays.run(DeadLetterAction.REPLAY, "replay_dlq", caller, id, idempotencyKey);
    }
}
