package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.domain.ActiveReplayConflict;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.etlops.domain.DlqInvalidStateException;
import dev.pti.api.etlops.domain.IdempotencyKeyReusedException;
import dev.pti.api.etlops.domain.ReplayAlreadyRunningException;
import dev.pti.api.etlops.domain.ReplayKind;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code replay} (E-44) and {@code confirm} (E-45) have in common (DOC-32 §7): in one transaction the dead letter
 * goes to {@code REPLAY_REQUESTED} (a conditional update), a {@code DLQ_RECORD} {@code replay_request} is written for
 * {@code etl-batch} to run (ADR-0013), and the action log gets its lines. The answer is the replay request.
 *
 * <p>A repeated {@code Idempotency-Key} with the same ask answers with the first request and writes nothing, not even a
 * log line (DOC-31 §8). The key is looked up before the update, because the dead letter no longer has its first
 * status; and again when the update finds nothing, because a request with the same key may have won a race.
 */
final class DeadLetterReplays {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplays.class);

    private final DeadLetterStore letters;
    private final ReplayRequestStore replays;
    private final TransactionRunner operatorTx;
    private final UiEventPublisher events;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final Supplier<UUID> ids;

    DeadLetterReplays(
            DeadLetterStore letters,
            ReplayRequestStore replays,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock,
            Supplier<UUID> ids) {
        this.letters = letters;
        this.replays = replays;
        this.operatorTx = operatorTx;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.ids = ids;
    }

    /** What the transaction did, so that the event goes out after it. */
    private record Done(
            Submitted<ReplayRequest> submitted, @Nullable UiEvent event) {}

    Submitted<ReplayRequest> run(
            DeadLetterAction action, String operation, Caller caller, UUID id, @Nullable String idempotencyKey) {
        return Measured.write(metrics, operation, Measured::of, () -> {
            String actor = caller.actor();
            Done done;
            try {
                done = operatorTx.inTransaction(() -> attempt(action, actor, id, idempotencyKey));
            } catch (ActiveReplayConflict e) {
                UUID existing = operatorTx
                        .inTransaction(() -> replays.activeRecordReplay(id))
                        .orElse(null);
                throw new ReplayAlreadyRunningException(
                        "A replay of this dead letter is already waiting or running.", existing);
            }
            if (done.event() != null) {
                events.publish(done.event());
                log.atInfo()
                        .addKeyValue("deadLetterId", id)
                        .addKeyValue("replayRequestId", done.submitted().value().id())
                        .addKeyValue("actor", actor)
                        .log("replay requested");
            }
            return done.submitted();
        });
    }

    private Done attempt(DeadLetterAction action, String actor, UUID id, @Nullable String key) {
        Optional<Submitted<ReplayRequest>> repeat = repeatOf(actor, id, key);
        if (repeat.isPresent()) {
            return new Done(repeat.get(), null);
        }
        Optional<DeadLetterStore.Changed> changed =
                letters.transition(id, action.from(), DeadLetterStatus.REPLAY_REQUESTED, null);
        if (changed.isEmpty()) {
            repeat = repeatOf(actor, id, key);
            if (repeat.isPresent()) {
                return new Done(repeat.get(), null);
            }
            DeadLetterDetail current =
                    letters.find(id).orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
            throw new DlqInvalidStateException(action, current.status());
        }
        Instant now = clock.realNow();
        DeadLetterStore.Changed before = changed.get();
        ReplayRequest draft = ReplayRequest.pendingRecord(ids.get(), before.source(), id, actor, key, now);
        ReplayRequest stored = replays.insert(draft).orElseThrow(IdempotencyKeyReusedException::new);
        if (action == DeadLetterAction.CONFIRM) {
            letters.log(id, "CONFIRMED", actor, before.categoryConfidence(), Map.of());
        }
        letters.log(
                id,
                "REPLAY_REQUESTED",
                actor,
                null,
                Map.of("replay_request_id", stored.id().toString()));
        UiEvent event = DeadLetterEvents.updated(
                now,
                id,
                before.source(),
                DeadLetterStatus.REPLAY_REQUESTED,
                before.previous(),
                "REPLAY_REQUESTED",
                actor);
        return new Done(Submitted.created(stored), event);
    }

    private Optional<Submitted<ReplayRequest>> repeatOf(String actor, UUID id, @Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }
        return replays.findByKey(actor, key).map(prior -> {
            if (prior.kind() != ReplayKind.DLQ_RECORD || !id.equals(prior.deadLetterId())) {
                throw new IdempotencyKeyReusedException();
            }
            return Submitted.repeated(prior);
        });
    }
}
