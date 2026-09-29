package dev.pti.etl.batch;

import dev.pti.common.error.DataException;
import dev.pti.etl.core.InboundMessage;

/**
 * A reader found a record it cannot turn into an item, e.g. a broken raw-zone line (DOC-22). Carries what the reader
 * could recover, so that {@link DeadLetterSkipListener} writes a dead letter for it.
 */
public class UnreadableRecordException extends DataException {

    private static final long serialVersionUID = 1L;

    private final transient InboundMessage message;

    public UnreadableRecordException(InboundMessage message, DataException error) {
        super(error.stage(), error.ruleId(), String.valueOf(error.getMessage()), error);
        this.message = message;
    }

    public InboundMessage message() {
        return message;
    }

    public DataException error() {
        return (DataException) getCause();
    }
}
