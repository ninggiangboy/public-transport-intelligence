package dev.pti.etl.dq.application.port;

import dev.pti.etl.dq.domain.PostWriteRule;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code ops.dq_check_result}: the last run of a rule and a new result row (DOC-16 §4). */
public interface PostWriteRuleStore {

    Optional<Instant> lastRun(PostWriteRule rule);

    /** DQ-23 keeps its population next to the sample, so the relative threshold survives a restart. */
    void insert(PostWriteRule rule, long violations, @Nullable Long population, String sample);
}
