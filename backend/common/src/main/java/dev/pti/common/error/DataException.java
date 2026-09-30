package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;
import org.jspecify.annotations.Nullable;

/**
 * A problem with one record (DOC-30 §1): skipped and dead-lettered, never retried. Carries no stack trace, because
 * the {@code bad-data} scenario raises thousands per second.
 */
public class DataException extends PtiException {

    private static final long serialVersionUID = 1L;

    private final DlqStage stage;
    private final @Nullable String ruleId;

    public DataException(DlqStage stage, @Nullable String ruleId, String message, @Nullable Throwable cause) {
        super(message, cause, false);
        this.stage = stage;
        this.ruleId = ruleId;
    }

    public DlqStage stage() {
        return stage;
    }

    public @Nullable String ruleId() {
        return ruleId;
    }

    /**
     * The {@code error_class} column of the dead letter (DOC-16 §1): the rule id for rule violations, otherwise the
     * short name of the exception that caused it.
     */
    public String errorClass() {
        if (ruleId != null && stage != DlqStage.SCHEMA) {
            return ruleId;
        }
        Throwable cause = getCause();
        return cause != null ? cause.getClass().getSimpleName() : getClass().getSimpleName();
    }
}
