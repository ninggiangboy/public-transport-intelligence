package dev.pti.etl.core;

import dev.pti.common.error.DataException;
import dev.pti.etl.rules.RuleContext;
import org.jspecify.annotations.Nullable;

/**
 * Parses, validates and maps one message (DOC-19 §4.2). Pure: no I/O and no side effects, because a Spring Batch
 * scan calls it again for every item and the result must be the same (DR-21, test B-16).
 */
public interface MessageProcessor {

    EtlSource source();

    /**
     * @throws DataException (with its stage and rule) when the message is malformed, breaks its schema or a
     *     per-record rule
     */
    WriteSet process(InboundMessage message, RuleContext context);

    /** The business key of a message that failed, when it parses that far; for the dead letter (DOC-22 §1.2). */
    @Nullable
    String businessKey(InboundMessage message);
}
