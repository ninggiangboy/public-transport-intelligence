package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;

/** A data quality rule (DQ-02 to DQ-13) rejected the record (DOC-16 §2). */
public class RuleViolationException extends DataException {

    private static final long serialVersionUID = 1L;

    public RuleViolationException(DlqStage stage, String ruleId, String message) {
        super(stage, ruleId, message, null);
    }
}
