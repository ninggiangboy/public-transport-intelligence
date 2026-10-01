package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.domain.DeadLetterAction;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/**
 * {@code GET /etl/dlq/{id}} (DOC-32 E-42): one dead letter with its payloads, Kafka position, action log and replays.
 * {@code allowedActions} says what the caller may do now (a viewer gets none), so that the screen needs no rules of its
 * own to enable its buttons.
 */
public final class GetDeadLetter {

    private final DeadLetterReader letters;
    private final TransactionRunner tx;

    public GetDeadLetter(DeadLetterReader letters, TransactionRunner tx) {
        this.letters = letters;
        this.tx = tx;
    }

    public DeadLetterDetail execute(Caller caller, UUID id) {
        DeadLetterDetail detail = tx.inTransaction(() -> letters.find(id))
                .orElseThrow(() -> new NotFoundException("The dead letter does not exist."));
        return detail.withAllowedActions(DeadLetterAction.allowedFor(detail.status(), caller.isOperator()));
    }
}
