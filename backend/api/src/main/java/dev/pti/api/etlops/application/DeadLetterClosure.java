package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.etlops.domain.DlqInvalidStateException;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code discard} (E-46) and {@code resolve} (E-47) have in common (DOC-32 §7): a conditional update to the closed
 * status with who and when, one line in the action log with the reason or note, and the dead letter as the answer.
 * Closing a dead letter that the same person already closed the same way is a 200 that writes nothing.
 */
final class DeadLetterClosure {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterClosure.class);

    private final DeadLetterStore letters;
    private final TransactionRunner operatorTx;
    private final UiEventPublisher events;
    private final WriteMetrics metrics;
    private final BusinessClock clock;

    DeadLetterClosure(
            DeadLetterStore letters,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        this.letters = letters;
        this.operatorTx = operatorTx;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    private record Done(
            DeadLetterDetail detail,
            boolean written,
            @Nullable UiEvent event) {}

    /**
     * @param action {@code DISCARD} or {@code RESOLVE}
     * @param detailKey {@code reason} or {@code note}: the key of the log line's details
     */
    DeadLetterDetail close(
            DeadLetterAction action, String operation, Caller caller, UUID id, String detailKey, String text) {
        return Measured.write(
                        metrics,
                        operation,
                        done -> done.written() ? WriteMetrics.CREATED : WriteMetrics.IDEMPOTENT,
                        () -> {
                            String actor = caller.actor();
                            Done done = operatorTx.inTransaction(() -> attempt(action, actor, id, detailKey, text));
                            if (done.event() != null) {
                                events.publish(done.event());
                                log.atInfo()
                                        .addKeyValue("deadLetterId", id)
                                        .addKeyValue("actor", actor)
                                        .log(
                                                action == DeadLetterAction.DISCARD
                                                        ? "dead letter discarded"
                                                        : "dead letter resolved");
                            }
                            return done;
                        })
                .detail();
    }

    private Done attempt(DeadLetterAction action, String actor, UUID id, String detailKey, String text) {
        Instant now = clock.realNow();
        DeadLetterStatus target = Objects.requireNonNull(action.to());
        Optional<DeadLetterStore.Changed> changed = letters.transition(id, action.from(), target, actor);
        if (changed.isPresent()) {
            letters.log(id, action.logAction(), actor, null, Map.of(detailKey, text));
            DeadLetterDetail detail = read(id);
            UiEvent event = DeadLetterEvents.updated(
                    now, id, changed.get().source(), target, changed.get().previous(), action.logAction(), actor);
            return new Done(forOperator(detail), true, event);
        }
        DeadLetterDetail current = read(id);
        if (current.status() == target && actor.equals(current.resolvedBy())) {
            return new Done(forOperator(current), false, null);
        }
        throw new DlqInvalidStateException(action, current.status());
    }

    private DeadLetterDetail read(UUID id) {
        return letters.find(id).orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
    }

    private static DeadLetterDetail forOperator(DeadLetterDetail detail) {
        return detail.withAllowedActions(DeadLetterAction.allowedFor(detail.status(), true));
    }
}
