package dev.pti.analytics.otp.application;

import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.otp.application.port.OtpScorecardStore;
import dev.pti.analytics.otp.application.port.OtpScorecardStore.Merge;
import dev.pti.analytics.otp.domain.OtpServiceDates;
import dev.pti.analytics.otp.domain.OtpSettings;
import dev.pti.analytics.otp.domain.OtpTolerances;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Scores the on-time performance of every route for one service date (DOC-23 §8): in one transaction it takes the
 * date's advisory lock and merges the day's rows. What counts as on time is the tolerance of the settings, written on
 * every row; the counting is the store's SQL.
 *
 * <p>The transaction is the caller's when there is one (a tasklet call, DOC-49 §5.1). The same instance serves a
 * recompute, which calls it for each day of its plan.
 */
public final class OtpScorecardCalculator {

    /** DOC-23 §12.1: a day of one network is one aggregation; a minute is a hung statement. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(60);

    /** DOC-23 §2.5: how long a job waits for the day's lock before its step fails. */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    private final OtpScorecardStore store;
    private final AnalyticsReferenceCache reference;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final TransactionRunner tx;
    private final RunReporter reporter;
    private final BusinessClock clock;
    private final OtpTolerances tolerances;

    public OtpScorecardCalculator(
            OtpScorecardStore store,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            RunReporter reporter,
            BusinessClock clock,
            OtpSettings settings) {
        this.store = store;
        this.reference = reference;
        this.lock = lock;
        this.limits = limits;
        this.tx = tx;
        this.reporter = reporter;
        this.clock = clock;
        this.tolerances = OtpTolerances.of(settings);
    }

    /**
     * @return the run of the day: {@code updated} is the number of rows upserted and {@code deleted} the number
     *     removed; {@code NOOP} when no feed is ACTIVE
     * @throws IllegalArgumentException when the date is not before today
     */
    public RunResult calculate(OtpDayRun run) {
        return reporter.report(
                Detector.OTP, run.serviceDate().toString(), run.trigger(), run.batchId(), () -> score(run));
    }

    private RunResult score(OtpDayRun run) {
        String scope = run.serviceDate().toString();
        if (!reference.hasActiveFeed()) {
            return RunResult.noop(Detector.OTP, scope, run.trigger(), run.batchId());
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(reference.agencyZone()).toLocalDate();
        OtpServiceDates.requireScorable(run.serviceDate(), today, null);
        Merge merge = tx.inTransaction(() -> {
            limits.statementTimeout(STATEMENT_TIMEOUT);
            lock.acquire(LockNames.otp(run.serviceDate()), LOCK_TIMEOUT);
            return store.recompute(run.serviceDate(), tolerances, now, run.batchId());
        });
        return new RunResult(
                Detector.OTP,
                scope,
                run.trigger(),
                Outcome.OK,
                run.batchId(),
                0,
                0,
                merge.upserted(),
                0,
                merge.deleted(),
                List.of());
    }
}
