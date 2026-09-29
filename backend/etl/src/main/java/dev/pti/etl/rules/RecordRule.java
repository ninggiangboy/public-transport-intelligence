package dev.pti.etl.rules;

import dev.pti.common.dq.DlqStage;
import java.util.Optional;

/** A rule evaluated on one record (DOC-16 §5). Pure: no I/O and no clock outside the context. */
public interface RecordRule<T> {

    String id();

    DlqStage stage();

    /** False for rules that replay skips (DQ-07). */
    boolean appliesDuringReplay();

    /** The English violation message, or empty when the record passes. */
    Optional<String> check(T record, RuleContext context);
}
