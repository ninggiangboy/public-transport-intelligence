package dev.pti.etl.dq.application;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.dq.application.port.PostWriteChecks;
import dev.pti.etl.dq.application.port.PostWriteRuleStore;
import dev.pti.etl.dq.domain.PostWriteRule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Runs the post-write rule of one step of {@code DataQualityJob} when its interval has passed (DOC-16 §3). One rule
 * per call ({@code CONTINUABLE}) so no transaction is long.
 */
public final class RunDataQualityChecks {

    /** A rule is due slightly before its interval ends, so a 5-minute rule is not skipped by schedule jitter. */
    static final Duration SLACK = Duration.ofSeconds(30);

    private final PostWriteRuleStore store;
    private final PostWriteChecks checks;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final Duration refundGrace;
    private final Predicate<String> enabled;
    private final LongSupplier activeFeedVersion;

    public RunDataQualityChecks(
            PostWriteRuleStore store,
            PostWriteChecks checks,
            BusinessClock clock,
            ZoneId agencyZone,
            Duration refundGrace,
            Predicate<String> enabled,
            LongSupplier activeFeedVersion) {
        this.store = store;
        this.checks = checks;
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.refundGrace = refundGrace;
        this.enabled = enabled;
        this.activeFeedVersion = activeFeedVersion;
    }

    /** @return true when the rule was enabled and due, and ran */
    public boolean runIfDue(PostWriteRule rule) {
        if (!enabled.test(rule.id()) || !isDue(rule)) {
            return false;
        }
        checks.run(rule, parameters());
        return true;
    }

    boolean isDue(PostWriteRule rule) {
        Instant now = clock.realNow();
        return store.lastRun(rule)
                .map(last -> !last.plus(rule.interval()).minus(SLACK).isAfter(now))
                .orElse(true);
    }

    private PostWriteChecks.Parameters parameters() {
        Instant businessNow = clock.instant();
        return new PostWriteChecks.Parameters(
                businessNow,
                clock.realNow(),
                LocalDate.ofInstant(businessNow, agencyZone),
                refundGrace,
                activeFeedVersion.getAsLong());
    }
}
