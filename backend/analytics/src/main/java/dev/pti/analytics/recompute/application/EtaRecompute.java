package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaPlan;
import dev.pti.analytics.eta.application.EtaPlanRequest;
import dev.pti.analytics.eta.application.EtaRouteRun;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * ETA recompute (DOC-23 §11.1): one forced run for the current hour. The table is built from the last 28 days, so
 * whatever the range was, the whole table is rebuilt; the item runs every route of the plan in its one transaction
 * and ends with the checkpoint, like the last route of the hourly job.
 */
public final class EtaRecompute implements DetectorRecompute {

    /** DOC-23 §12.1: a recompute item allows each statement this long. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(90);

    private final EtaRunPlanner planner;
    private final EtaAggregator aggregator;
    private final AnalyticsReferenceCache reference;
    private final TransactionRunner tx;
    private final TransactionLimits limits;
    private final BusinessClock clock;

    public EtaRecompute(
            EtaRunPlanner planner,
            EtaAggregator aggregator,
            AnalyticsReferenceCache reference,
            TransactionRunner tx,
            TransactionLimits limits,
            BusinessClock clock) {
        this.planner = planner;
        this.aggregator = aggregator;
        this.reference = reference;
        this.tx = tx;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public Detector detector() {
        return Detector.ETA;
    }

    @Override
    public List<WorkItem> plan(Instant from, Instant to) {
        if (!reference.hasActiveFeed()) {
            return List.of();
        }
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        return List.of(new WorkItem(Detector.ETA, hour.toString(), hour, hour));
    }

    @Override
    public DetectorStats execute(WorkItem item, UUID batchId) {
        return tx.inTransaction(() -> {
            limits.statementTimeout(STATEMENT_TIMEOUT);
            EtaPlan plan = planner.plan(new EtaPlanRequest(item.from(), true));
            if (plan.status() != EtaPlan.Status.RUN || plan.routeIds().isEmpty()) {
                return DetectorStats.NONE;
            }
            List<String> routes = plan.routeIds();
            int upserted = 0;
            int deleted = 0;
            for (int i = 0; i < routes.size(); i++) {
                boolean last = i == routes.size() - 1;
                EtaRouteRun.Completion completion = last ? new EtaRouteRun.Completion(plan.watermark(), null) : null;
                RunResult result = aggregator.aggregate(
                        new EtaRouteRun(routes.get(i), item.from(), batchId, Trigger.RECOMPUTE, completion));
                upserted += result.updated();
                deleted += result.deleted();
            }
            return new DetectorStats(1, upserted, deleted);
        });
    }
}
