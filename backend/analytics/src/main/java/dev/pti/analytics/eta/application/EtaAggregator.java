package dev.pti.analytics.eta.application;

import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.eta.application.port.EtaAggregateStore.Merge;
import dev.pti.analytics.eta.domain.EtaSettings;
import dev.pti.analytics.eta.domain.EtaWindow;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.util.List;

/**
 * Aggregates the historical ETA of one route (DOC-23 §7.1): in one transaction it takes the table's advisory lock,
 * recomputes the route's rows for the 28 days before the run's hour and merges them, and on the last route of a run
 * stores the checkpoint. The statistics are computed by the store's SQL; the window, the lock and the limits are here.
 *
 * <p>The transaction is the caller's when there is one (a tasklet call, DOC-49 §5.1): the lock and the checkpoint then
 * commit or roll back together with the step's context, which is what makes a restart continue from the right route.
 * The same instance serves a recompute, which calls it for each route of a forced plan.
 */
public final class EtaAggregator {

    /** DOC-23 §12.1: one route of 28 days takes under a second, so a minute is a hung statement. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(60);

    /** DOC-23 §2.5: how long a job waits for the table's lock before its step fails. */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    private final EtaAggregateStore store;
    private final AnalyticsReferenceCache reference;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final TransactionRunner tx;
    private final RunReporter reporter;
    private final EtaSettings settings;

    public EtaAggregator(
            EtaAggregateStore store,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            RunReporter reporter,
            EtaSettings settings) {
        this.store = store;
        this.reference = reference;
        this.lock = lock;
        this.limits = limits;
        this.tx = tx;
        this.reporter = reporter;
        this.settings = settings;
    }

    /**
     * @return the run of the route: {@code updated} is the number of rows upserted and {@code deleted} the number
     *     removed; {@code NOOP} when no feed is ACTIVE
     */
    public RunResult aggregate(EtaRouteRun run) {
        return reporter.report(Detector.ETA, run.routeId(), run.trigger(), run.batchId(), () -> aggregateRoute(run));
    }

    private RunResult aggregateRoute(EtaRouteRun run) {
        if (!reference.hasActiveFeed()) {
            return RunResult.noop(Detector.ETA, run.routeId(), run.trigger(), run.batchId());
        }
        EtaWindow window = EtaWindow.of(run.hour(), settings.window(), reference.agencyZone());
        Merge merge = tx.inTransaction(() -> {
            limits.statementTimeout(STATEMENT_TIMEOUT);
            lock.acquire(LockNames.eta(), LOCK_TIMEOUT);
            Merge merged = store.recompute(run.routeId(), window, run.hour(), run.batchId());
            EtaRouteRun.Completion completion = run.completion();
            if (completion != null) {
                store.saveCheckpoint(completion.watermark(), run.hour(), completion.jobExecutionId());
            }
            return merged;
        });
        return new RunResult(
                Detector.ETA,
                run.routeId(),
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
