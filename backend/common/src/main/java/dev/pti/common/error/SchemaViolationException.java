package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;
import java.util.List;

/** DQ-01: the message does not match its JSON Schema or its DTO constraints (DOC-16 §2). */
public class SchemaViolationException extends DataException {

    private static final long serialVersionUID = 1L;

    public static final String RULE_ID = "DQ-01";

    private final List<String> violations;

    public SchemaViolationException(List<String> violations) {
        super(DlqStage.SCHEMA, RULE_ID, violations.isEmpty() ? "Schema violation" : violations.getFirst(), null);
        this.violations = List.copyOf(violations);
    }

    public SchemaViolationException(String violation) {
        this(List.of(violation));
    }

    /** Every violation found, the first of which is the message. */
    public List<String> violations() {
        return violations;
    }

    @Override
    public String errorClass() {
        return "SchemaViolation";
    }
}
