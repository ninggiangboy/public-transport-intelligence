package dev.pti.etl.write.domain;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.etl.core.domain.InboundMessage;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row for {@code ops.dead_letter} (DOC-22 §1.2), before the payload is scrubbed and truncated. */
public record DeadLetter(
        InboundMessage message,
        DlqStage stage,
        @Nullable String ruleId,
        String errorClass,
        String errorMessage,
        @Nullable String businessKey,
        UUID batchId) {

    /** Error class of a write rejected by the database during a scan (DOC-22 §1.1). */
    public static final String LOAD_ERROR_CLASS = "DataIntegrityViolation";

    public static DeadLetter of(
            InboundMessage message, DataException error, @Nullable String businessKey, UUID batchId) {
        return new DeadLetter(
                message,
                error.stage(),
                error.ruleId(),
                error.errorClass(),
                String.valueOf(error.getMessage()),
                businessKey,
                batchId);
    }
}
