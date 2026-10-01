package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;
import java.util.Map;

/** The dead letter is in a state that does not allow the action (409 {@code dlq-invalid-state}, DOC-32 §7). */
public class DlqInvalidStateException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final DeadLetterStatus currentStatus;

    public DlqInvalidStateException(DeadLetterAction action, DeadLetterStatus currentStatus) {
        super("The dead letter is " + currentStatus + ", which does not allow " + action.wire() + ".");
        this.currentStatus = currentStatus;
    }

    @Override
    public ProblemType type() {
        return ProblemType.DLQ_INVALID_STATE;
    }

    @Override
    public Map<String, Object> extensions() {
        return Map.of("currentStatus", currentStatus.name());
    }
}
