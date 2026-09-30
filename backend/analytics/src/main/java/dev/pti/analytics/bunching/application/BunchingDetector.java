package dev.pti.analytics.bunching.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertEvents;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter.Previous;
import dev.pti.analytics.bunching.application.port.BunchingMetrics;
import dev.pti.analytics.bunching.application.port.BunchingStateStore;
import dev.pti.analytics.bunching.application.port.BunchingStateStore.StoredState;
import dev.pti.analytics.bunching.application.port.VehicleHistoryReader;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.BunchingEvaluator;
import dev.pti.analytics.bunching.domain.BunchingMessages;
import dev.pti.analytics.bunching.domain.BunchingThresholds;
import dev.pti.analytics.bunching.domain.EpisodeChangeLog;
import dev.pti.analytics.bunching.domain.PairStateChanges;
import dev.pti.analytics.bunching.domain.PairStateMachine;
import dev.pti.analytics.bunching.domain.PassSource;
import dev.pti.analytics.bunching.domain.SkipReason;
import dev.pti.analytics.bunching.domain.TickEvaluation;
import dev.pti.analytics.bunching.domain.VehicleTrack;
import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.EventTimeGrid;
import dev.pti.analytics.core.domain.EventTimeGrid.CatchUp;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bus bunching detection (DR-30, DOC-23 §5): advances one route along the 15-second event-time grid up to its
 * watermark, evaluates every vehicle pair at each grid point, keeps the pair states, and writes the episodes and their
 * alerts in the same transaction. The events of the run go back to the caller, which publishes them after the commit
 * (DOC-49 §5.2).
 *
 * <p>The order inside {@link #advance} is that of DOC-23 §5.6: the watermark and the cursor are read outside the write
 * transaction, so a route that is already up to date costs two reads and no lock; the work itself runs in one
 * {@code REQUIRES_NEW} transaction under the route's advisory lock.
 */
public class BunchingDetector implements RouteDetector {

    /** DOC-23 §12.1. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration NO_FEED_WARNING_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(BunchingDetector.class);

    private final BunchingStateStore store;
    private final VehicleHistoryReader history;
    private final BunchingEpisodeWriter episodes;
    private final AlertWriter alerts;
    private final AnalyticsReferenceCache reference;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final AnalyticsMetrics metrics;
    private final BunchingMetrics bunchingMetrics;
    private final TransactionRunner tx;
    private final BusinessClock clock;
    private final BunchingThresholds thresholds;
    private final BunchingEvaluator evaluator;
    private final AtomicReference<@Nullable Instant> lastNoFeedWarning = new AtomicReference<>();

    public BunchingDetector(
            BunchingStateStore store,
            VehicleHistoryReader history,
            BunchingEpisodeWriter episodes,
            AlertWriter alerts,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            AnalyticsMetrics metrics,
            BunchingMetrics bunchingMetrics,
            TransactionRunner tx,
            BusinessClock clock,
            BunchingThresholds thresholds) {
        this.store = store;
        this.history = history;
        this.episodes = episodes;
        this.alerts = alerts;
        this.reference = reference;
        this.lock = lock;
        this.limits = limits;
        this.metrics = metrics;
        this.bunchingMetrics = bunchingMetrics;
        this.tx = tx;
        this.clock = clock;
        this.thresholds = thresholds;
        this.evaluator = new BunchingEvaluator(thresholds, new ReferenceBunchingSchedule(reference));
    }

    @Override
    public Detector detector() {
        return Detector.BUNCHING;
    }

    /** True for a route of the ACTIVE feed whose {@code route_type} is one of {@code bunching.route-types}. */
    @Override
    public boolean enabledFor(String routeId) {
        if (!reference.hasActiveFeed()) {
            warnNoActiveFeed();
            return false;
        }
        return reference
                .route(routeId)
                .map(route -> thresholds.routeTypes().contains(route.routeType()))
                .orElse(false);
    }

    @Override
    public RunResult advance(String routeId, Trigger trigger, RunContext context) {
        UUID batchId = context.batchId();
        if (!enabledFor(routeId)) {
            return RunResult.noop(Detector.BUNCHING, routeId, trigger, batchId);
        }
        Instant now = clock.instant();
        ZoneId zone = reference.agencyZone();
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant newest = history.newestEventTime(routeId, new DateRange(today.minusDays(1), today))
                .orElse(null);
        Instant watermark =
                EventTimeGrid.watermark(now, newest, thresholds.allowedLateness(), thresholds.idleTimeout());
        Instant last = EventTimeGrid.floorGrid(watermark, thresholds.evaluationInterval());
        Optional<Instant> cursor = store.cursor(routeId);
        if (cursor.isPresent() && !last.isAfter(cursor.get())) {
            return RunResult.noop(Detector.BUNCHING, routeId, trigger, batchId);
        }
        Advanced advanced = tx.inNewTransaction(() -> advanceLocked(routeId, trigger, context, watermark, last, zone));
        report(routeId, advanced.work());
        return advanced.result();
    }

    @Override
    public List<String> routesNeedingTick() {
        return store.routesNeedingTick();
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        return store.cursor(routeId);
    }

    /** The write transaction: everything from the lock to the last row. */
    private Advanced advanceLocked(
            String routeId, Trigger trigger, RunContext context, Instant watermark, Instant last, ZoneId zone) {
        UUID batchId = context.batchId();
        limits.statementTimeout(STATEMENT_TIMEOUT);
        if (!lock.tryAcquire(LockNames.bunching(routeId))) {
            return Advanced.without(RunResult.skippedLocked(Detector.BUNCHING, routeId, trigger, batchId));
        }
        Duration interval = thresholds.evaluationInterval();
        // Read again under the lock: another pod may have advanced the route since the fast path looked.
        Instant cursor = store.cursor(routeId)
                .orElseGet(() -> EventTimeGrid.initialCursor(context.minEventTs(), last, interval));
        if (!last.isAfter(cursor)) {
            return Advanced.without(RunResult.noop(Detector.BUNCHING, routeId, trigger, batchId));
        }
        Work work = new Work();
        CatchUp catchUp = EventTimeGrid.limitCatchUp(cursor, watermark, thresholds.maxCatchUp(), interval);
        if (catchUp.skippedPoints() > 0) {
            boolean hadData = history.anyPositionIn(
                    routeId, ServiceDates.covering(cursor, catchUp.cursor(), zone), cursor, catchUp.cursor());
            work.skippedFrom = cursor;
            work.skippedTo = catchUp.cursor();
            work.skippedPoints = catchUp.skippedPoints();
            work.skippedHadData = hadData;
        }
        Instant from = catchUp.cursor()
                .plus(interval)
                .minus(thresholds.leaderLookback())
                .minus(thresholds.positionMaxAge());
        List<VehicleTrack> tracks =
                VehicleTrack.group(history.positions(routeId, ServiceDates.covering(from, last, zone), from, last));
        StoredState stored = store.load(routeId);
        PairStateMachine machine =
                PairStateMachine.restore(routeId, thresholds, stored.pairStates(), stored.openEpisodes());
        EpisodeChangeLog changes = new EpisodeChangeLog();
        int gridPoints = 0;
        for (Instant tick : EventTimeGrid.pointsAfter(catchUp.cursor(), last, interval)) {
            TickEvaluation evaluation = evaluator.evaluate(routeId, tick, tracks);
            work.count(evaluation);
            changes.add(machine.apply(evaluation));
            gridPoints++;
        }
        store.save(
                routeId,
                PairStateChanges.between(stored.pairStates(), machine.states().values()));
        store.saveCursor(routeId, last);
        List<InsightEvent> events = new ArrayList<>();
        int updated = writeEpisodes(routeId, changes, batchId, context, work, events);
        RunResult result = new RunResult(
                Detector.BUNCHING,
                routeId,
                trigger,
                Outcome.OK,
                batchId,
                gridPoints,
                work.opened.size(),
                updated,
                work.closed.size(),
                0,
                events);
        return new Advanced(result, work);
    }

    /**
     * Upserts the episodes that changed and writes their alerts, in the order of DOC-23 §10.3: the event of the
     * episode before the event of its alert. Only a real change emits: the row did not exist, or was open and is now
     * closed, or the alert statement returned a row. Running the same data again therefore emits nothing.
     *
     * @return how many episodes were updated and stay open
     */
    private int writeEpisodes(
            String routeId,
            EpisodeChangeLog changes,
            UUID batchId,
            RunContext context,
            Work work,
            List<InsightEvent> events) {
        Optional<RouteInfo> route = reference.route(routeId);
        String routeLabel = route.map(RouteInfo::label).orElse(routeId);
        Instant sourceRecordTs = context.sourceRecordTs();
        Instant committedAt = context.committedAt();
        int updated = 0;
        for (EpisodeChangeLog.Entry entry : changes.entries()) {
            Previous previous = episodes.upsert(entry.latest(), batchId);
            BunchingEpisode opened = entry.opened();
            if (opened != null) {
                if (previous == Previous.ABSENT) {
                    work.opened.add(opened);
                    events.add(BunchingMessages.opened(opened, sourceRecordTs, committedAt));
                }
                String directionLabel = route.map(r -> r.directionLabel(opened.directionId()))
                        .orElse("Direction " + opened.directionId());
                alerts.open(BunchingMessages.alert(opened, routeLabel, directionLabel))
                        .ifPresent(alert -> events.add(AlertEvents.created(alert, sourceRecordTs, committedAt)));
            }
            if (entry.closed()) {
                BunchingEpisode closed = entry.latest();
                if (previous != Previous.CLOSED) {
                    work.closed.add(closed);
                    events.add(BunchingMessages.closed(closed, sourceRecordTs, committedAt));
                }
                alerts.resolve(AlertType.BUNCHING.dedupKey(closed.id()), BunchingMessages.closePatch(closed))
                        .ifPresent(alert -> events.add(AlertEvents.updated(alert, sourceRecordTs, committedAt)));
            } else if (previous != Previous.ABSENT) {
                updated++;
            }
        }
        return updated;
    }

    /** Metrics and logs of a run, after its transaction has committed: a rolled-back run must not leave any. */
    private void report(String routeId, @Nullable Work work) {
        if (work == null) {
            return;
        }
        work.evaluated.forEach(bunchingMetrics::evaluated);
        work.skipped.forEach(bunchingMetrics::skipped);
        if (work.skippedPoints > 0 && work.skippedHadData) {
            metrics.skippedTicks(Detector.BUNCHING, work.skippedPoints);
            log.atWarn()
                    .addKeyValue("detector", Detector.BUNCHING.tag())
                    .addKeyValue("scope", routeId)
                    .addKeyValue("from", work.skippedFrom)
                    .addKeyValue("to", work.skippedTo)
                    .addKeyValue("count", work.skippedPoints)
                    .log("analytics grid points skipped");
        } else if (work.skippedPoints > 0) {
            log.atDebug()
                    .addKeyValue("detector", Detector.BUNCHING.tag())
                    .addKeyValue("scope", routeId)
                    .addKeyValue("from", work.skippedFrom)
                    .addKeyValue("to", work.skippedTo)
                    .addKeyValue("count", work.skippedPoints)
                    .log("analytics grid points skipped, no data in them");
        }
        for (BunchingEpisode opened : work.opened) {
            bunchingMetrics.episodeOpened();
            log.atInfo()
                    .addKeyValue("episodeId", opened.id())
                    .addKeyValue("routeId", opened.routeId())
                    .addKeyValue("directionId", opened.directionId())
                    .addKeyValue("vehicleLeader", opened.leader())
                    .addKeyValue("vehicleFollower", opened.follower())
                    .addKeyValue("gapSeconds", opened.lastGapSeconds())
                    .addKeyValue("headwaySeconds", opened.scheduledHeadwaySeconds())
                    .log("bunching episode opened");
        }
        for (BunchingEpisode closed : work.closed) {
            bunchingMetrics.episodeClosed(closed.closeReason());
            log.atInfo()
                    .addKeyValue("episodeId", closed.id())
                    .addKeyValue("routeId", closed.routeId())
                    .addKeyValue("directionId", closed.directionId())
                    .addKeyValue("vehicleLeader", closed.leader())
                    .addKeyValue("vehicleFollower", closed.follower())
                    .addKeyValue("gapSeconds", closed.lastGapSeconds())
                    .addKeyValue("headwaySeconds", closed.scheduledHeadwaySeconds())
                    .addKeyValue("closeReason", closed.closeReason())
                    .log("bunching episode closed");
        }
    }

    /** DOC-23 §15: without an ACTIVE feed nothing can be evaluated; say so, at most once a minute. */
    private void warnNoActiveFeed() {
        Instant now = clock.instant();
        Instant previous = lastNoFeedWarning.get();
        if ((previous == null || now.isAfter(previous.plus(NO_FEED_WARNING_INTERVAL)))
                && lastNoFeedWarning.compareAndSet(previous, now)) {
            log.warn("bunching detection is idle: there is no ACTIVE feed version yet");
        }
    }

    /** What the transaction returns: the result and, when work was done, what to report after the commit. */
    private record Advanced(RunResult result, @Nullable Work work) {

        static Advanced without(RunResult result) {
            return new Advanced(result, null);
        }
    }

    /** Counters and lists of one run, filled inside the transaction and reported after it. */
    private static final class Work {

        final Map<PassSource, Long> evaluated = new EnumMap<>(PassSource.class);
        final Map<SkipReason, Long> skipped = new EnumMap<>(SkipReason.class);
        final List<BunchingEpisode> opened = new ArrayList<>();
        final List<BunchingEpisode> closed = new ArrayList<>();
        long skippedPoints;
        boolean skippedHadData;

        @Nullable
        Instant skippedFrom;

        @Nullable
        Instant skippedTo;

        void count(TickEvaluation evaluation) {
            evaluation.evaluations().forEach(e -> evaluated.merge(e.source(), 1L, Long::sum));
            evaluation.skipped().values().forEach(reason -> skipped.merge(reason, 1L, Long::sum));
        }
    }
}
