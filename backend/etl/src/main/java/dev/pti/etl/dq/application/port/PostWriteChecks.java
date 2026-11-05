package dev.pti.etl.dq.application.port;

import dev.pti.etl.dq.domain.PostWriteRule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Runs one post-write rule against the warehouse, behind a savepoint so that a failure of the rule does not fail a
 * run (DOC-16 §3). The parameters are the business times the statement reads.
 */
public interface PostWriteChecks {

    void run(PostWriteRule rule, Parameters parameters);

    record Parameters(
            Instant businessNow, Instant realNow, LocalDate today, Duration refundGrace, long activeFeedVersionId) {}
}
