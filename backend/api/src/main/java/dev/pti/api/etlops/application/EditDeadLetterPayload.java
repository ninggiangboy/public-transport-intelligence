package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.EditedPayloadChecker;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.DeadLetterDetail;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PUT /etl/dlq/{id}/payload} (DOC-32 E-43, DOC-22 §2): stores a corrected payload that a replay uses instead of
 * the raw one. The status is checked first (409), then the payload (422); nothing is written when either fails. The
 * raw payload is never touched, an edit overwrites the previous edit, and the action log gets {@code EDITED} with the
 * paths that changed, not their values.
 */
public final class EditDeadLetterPayload {

    private static final Logger log = LoggerFactory.getLogger(EditDeadLetterPayload.class);

    private final DeadLetterStore letters;
    private final EditedPayloadChecker checker;
    private final TransactionRunner operatorTx;
    private final UiEventPublisher events;
    private final WriteMetrics metrics;
    private final BusinessClock clock;

    public EditDeadLetterPayload(
            DeadLetterStore letters,
            EditedPayloadChecker checker,
            TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        this.letters = letters;
        this.checker = checker;
        this.operatorTx = operatorTx;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    private record Done(DeadLetterDetail detail, UiEvent event) {}

    public DeadLetterDetail execute(Caller caller, UUID id, String payload) {
        return Measured.write(metrics, "edit_payload", done -> WriteMetrics.CREATED, () -> {
                    String actor = caller.actor();
                    Done done = operatorTx.inTransaction(() -> attempt(actor, id, payload));
                    events.publish(done.event());
                    log.atInfo()
                            .addKeyValue("deadLetterId", id)
                            .addKeyValue("actor", actor)
                            .log("dead letter payload edited");
                    return done;
                })
                .detail();
    }

    private Done attempt(String actor, UUID id, String payload) {
        DeadLetterDetail current =
                letters.find(id).orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
        if (!DeadLetterAction.EDIT.allows(current.status())) {
            throw new DlqInvalidStateException(DeadLetterAction.EDIT, current.status());
        }
        EditedPayloadChecker.Checked checked = checker.check(current.item().source(), current.rawPayload(), payload);
        Optional<DeadLetterStore.Changed> changed =
                letters.saveEditedPayload(id, DeadLetterAction.EDIT.from(), checked.json());
        if (changed.isEmpty()) {
            // Moved on between the read and the write: the dead letter is no longer editable.
            DeadLetterDetail moved =
                    letters.find(id).orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
            throw new DlqInvalidStateException(DeadLetterAction.EDIT, moved.status());
        }
        letters.log(id, "EDITED", actor, null, Map.of("changed_paths", checked.changedPaths()));
        DeadLetterDetail detail =
                letters.find(id).orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
        Instant now = clock.realNow();
        UiEvent event = DeadLetterEvents.updated(
                now, id, changed.get().source(), detail.status(), changed.get().previous(), "EDITED", actor);
        return new Done(detail.withAllowedActions(DeadLetterAction.allowedFor(detail.status(), true)), event);
    }
}
