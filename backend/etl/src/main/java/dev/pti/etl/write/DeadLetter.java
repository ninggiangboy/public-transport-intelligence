package dev.pti.etl.write;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.etl.core.InboundMessage;
import java.sql.SQLException;
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

    /** A row the database refused (SQLState 22xxx or 23xxx) while writing one message on its own. */
    public static DeadLetter load(InboundMessage message, Throwable error, @Nullable String businessKey, UUID batchId) {
        return new DeadLetter(message, DlqStage.LOAD, null, LOAD_ERROR_CLASS, loadMessage(error), businessKey, batchId);
    }

    private static String loadMessage(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return "[" + sql.getSQLState() + "] " + sql.getMessage();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return String.valueOf(error.getMessage());
    }
}
