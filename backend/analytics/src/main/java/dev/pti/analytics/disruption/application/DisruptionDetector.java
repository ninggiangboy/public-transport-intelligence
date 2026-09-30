package dev.pti.analytics.disruption.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertEvents;
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
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.disruption.application.port.DisruptionMetrics;
import dev.pti.analytics.disruption.application.port.DisruptionStore;
import dev.pti.analytics.disruption.domain.Arrival;
import dev.pti.analytics.disruption.domain.ArrivalSeries;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.DirectionState;
import dev.pti.analytics.disruption.domain.DisruptionAlerts;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import dev.pti.analytics.disruption.domain.DisruptionReplay;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import dev.pti.analytics.disruption.domain.EpisodeChange;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds service disruptions on a route (DOC-23 §6, DR-31): the mean delay of the arrivals of the last ten minutes, per
 * direction and per minute, is compared with an EWMA baseline of that route and direction; a high value in two buckets
 * in a row opens an episode, and a low one in three closes it. The maths is in {@link DisruptionReplay} and the state
 * machine under it; this class is the order of work of one {@code advance} (§6.4): read the watermark outside the
 * write transaction, then in one new transaction take the route's lock, process the buckets, write episodes, alerts,
 * state and snapshots, and return the UI events for the caller to publish after the commit (§12.1).
 *
 * <p>The shared metrics of a run and its log line belong to the caller; this class adds the episode metrics and the
 * episode logs of §14.2.
 */
public final class DisruptionDetector implements RouteDetector {

    private static final Logger log = LoggerFactory.getLogger(DisruptionDetector.class);

    private static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NO_FEED_WARNING_INTERVAL = Duration.ofMinutes(1);

    private final boolean enabled;
    private final DisruptionThresholds thresholds;
    private final DisruptionStore store;
    private final AnalyticsReferenceCache reference;
    private final AlertWriter alertWriter;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final TransactionRunner tx;
    private final AnalyticsMetrics metrics;
    private final DisruptionMetrics disruptionMetrics;
    private final BusinessClock clock;
    private final DisruptionReplay replay;
    private final DisruptionAlerts alerts;
    private final AtomicReference<@Nullable Instant> lastNoFeedWarning = new AtomicReference<>();

    public DisruptionDetector(
            boolean enabled,
            DisruptionThresholds thresholds,
            DisruptionStore store,
            AnalyticsReferenceCache reference,
            AlertWriter alertWriter,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            AnalyticsMetrics metrics,
            DisruptionMetrics disruptionMetrics,
            BusinessClock clock) {
        this.enabled = enabled;
        this.thresholds = thresholds;
        this.store = store;
        this.reference = reference;
        this.alertWriter = alertWriter;
        this.lock = lock;
        this.limits = limits;
        this.tx = tx;
        this.metrics = metrics;
        this.disruptionMetrics = disruptionMetrics;
        this.clock = clock;
        this.replay = new DisruptionReplay(thresholds);
        this.alerts = new DisruptionAlerts(thresholds.severityHighZ());
    }

    @Override
    public Detector detector() {
        return Detector.DISRUPTION;
    }

    /**
     * False when the detector is off or the route's {@code route_type} is not in {@code disruption.route-types}. While
     * no feed is ACTIVE the route type is unknown, so the answer is true and {@link #advance} reports {@code NOOP}.
     */
    @Override
    public boolean enabledFor(String routeId) {
        if (!enabled) {
            return false;
        }
        if (!reference.hasActiveFeed()) {
            return true;
        }
        return reference
                .route(routeId)
                .map(route -> thresholds.routeTypes().contains(route.routeType()))
                .orElse(false);
    }

    @Override
    public RunResult advance(String routeId, Trigger trigger, RunContext context) {
        if (!enabled) {
            return RunResult.noop(Detector.DISRUPTION, routeId, trigger, context.batchId());
        }
        if (!reference.hasActiveFeed()) {
            warnNoFeed();
            return RunResult.noop(Detector.DISRUPTION, routeId, trigger, context.batchId());
        }
        if (!enabledFor(routeId)) {
            return RunResult.noop(Detector.DISRUPTION, routeId, trigger, context.batchId());
        }
        Instant now = clock.instant();
        ZoneId zone = reference.agencyZone();
        // Read-only and outside the write transaction: a run with nothing to do holds no lock (DOC-23 §12.1).
        Instant newest = store.newestUpdate(routeId, ServiceDates.covering(now, now, zone))
                .orElse(null);
        Instant watermark =
                EventTimeGrid.watermark(now, newest, thresholds.allowedLateness(), thresholds.idleTimeout());
        Instant last = EventTimeGrid.floorGrid(watermark, thresholds.bucket());
        Optional<Instant> cursor = store.cursor(routeId);
        if (cursor.isPresent() && !last.isAfter(cursor.get())) {
            return RunResult.noop(Detector.DISRUPTION, routeId, trigger, context.batchId());
        }
        Advanced advanced = tx.inNewTransaction(() -> advanceLocked(routeId, trigger, context, zone, watermark, last));
        advanced.opened().forEach(this::episodeOpened);
        advanced.closed().forEach(this::episodeClosed);
        return advanced.result();
    }

    @Override
    public List<String> routesNeedingTick() {
        return enabled ? store.routesNeedingTick() : List.of();
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        return store.cursor(routeId);
    }

    /** What one transaction did: the result for the caller and the episodes to log and count once it has committed. */
    private record Advanced(RunResult result, List<DisruptionEpisode> opened, List<DisruptionEpisode> closed) {

        static Advanced nothing(RunResult result) {
            return new Advanced(result, List.of(), List.of());
        }
    }

    private Advanced advanceLocked(
            String routeId, Trigger trigger, RunContext context, ZoneId zone, Instant watermark, Instant last) {
        limits.statementTimeout(STATEMENT_TIMEOUT);
        if (!lock.tryAcquire(LockNames.disruption(routeId))) {
            return Advanced.nothing(RunResult.skippedLocked(Detector.DISRUPTION, routeId, trigger, context.batchId()));
        }
        Instant initial = EventTimeGrid.initialCursor(context.minEventTs(), last, thresholds.bucket());
        Map<Integer, BaselineState> stored = store.baselines(routeId);
        Episodes episodes = new Episodes(alerts::severity);
        Map<Integer, DirectionState> states = new LinkedHashMap<>();
        for (int directionId : DisruptionReplay.DIRECTIONS) {
            states.put(directionId, loadState(stored.get(directionId), initial, episodes));
        }
        // Re-read under the lock: another pod may have advanced the route since the watermark check.
        Instant cursor = states.values().stream()
                .map(state -> state.baseline().lastBucket())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        if (!last.isAfter(cursor)) {
            return Advanced.nothing(RunResult.noop(Detector.DISRUPTION, routeId, trigger, context.batchId()));
        }
        CatchUp catchUp = EventTimeGrid.limitCatchUp(cursor, watermark, thresholds.maxCatchUp(), thresholds.bucket());
        if (catchUp.skippedPoints() > 0) {
            reportSkipped(routeId, cursor, catchUp, zone);
        }
        List<Instant> bucketEnds = EventTimeGrid.pointsAfter(catchUp.cursor(), last, thresholds.bucket());
        Instant arrivalsFrom = bucketEnds.getFirst().minus(thresholds.window());
        List<Arrival> arrivals =
                store.arrivals(routeId, arrivalsFrom, last, ServiceDates.covering(arrivalsFrom, last, zone));

        DisruptionReplay.Result replayed = replay.run(
                routeId,
                states,
                new ArrivalSeries(arrivals, thresholds),
                bucketEnds,
                (hour, directionId, state) -> store.saveSnapshot(hour, routeId, directionId, state));
        replayed.changes().forEach(episodes::apply);

        List<InsightEvent> events = new ArrayList<>();
        writeEpisodes(episodes, routeId, context, events);
        replayed.states().forEach((directionId, state) -> store.saveBaseline(routeId, directionId, state.baseline()));

        RunResult result = new RunResult(
                Detector.DISRUPTION,
                routeId,
                trigger,
                Outcome.OK,
                context.batchId(),
                bucketEnds.size(),
                episodes.opened().size(),
                episodes.updatedCount(),
                episodes.closed().size(),
                0,
                events);
        return new Advanced(result, episodes.opened(), episodes.closed());
    }

    /** The stored state of a direction with its open episode, or a new state that starts before the first bucket. */
    private DirectionState loadState(@Nullable BaselineState stored, Instant initial, Episodes episodes) {
        if (stored == null) {
            return DirectionState.initial(initial);
        }
        UUID openId = stored.openEpisodeId();
        if (openId == null) {
            return new DirectionState(stored, null);
        }
        Optional<DisruptionEpisode> open = store.openEpisode(openId);
        if (open.isEmpty()) {
            // Deleted by a recompute or already closed elsewhere: the baseline must not point at it any more.
            log.warn("The open disruption episode {} of a baseline is gone; clearing the reference", openId);
            return new DirectionState(
                    new BaselineState(
                            stored.mean(),
                            stored.variance(),
                            stored.bucketCount(),
                            stored.lastBucket(),
                            stored.consecutiveHigh(),
                            0,
                            null),
                    null);
        }
        episodes.loaded(open.get());
        return new DirectionState(stored, open.get());
    }

    /**
     * Upserts every episode the run touched and writes its alert in the same transaction (DOC-23 §10.2). An opened
     * episode gets its alert at the severity of its first bucket, raised to 2 once its peak reaches
     * {@code severity-high-z}; a closed one resolves it. An event is produced only when the alert statement returned a
     * row, so a run over data that was analysed before publishes nothing (§10.3).
     */
    private void writeEpisodes(Episodes episodes, String routeId, RunContext context, List<InsightEvent> events) {
        RouteInfo route = reference.route(routeId).orElseThrow();
        Instant sourceRecordTs = context.sourceRecordTs();
        Instant committedAt = context.committedAt();
        for (Episodes.Trace trace : episodes.touched()) {
            DisruptionEpisode latest = trace.latest();
            store.saveEpisode(latest, context.batchId());
            String dedupKey = DisruptionAlerts.dedupKey(latest);
            DisruptionEpisode opened = trace.openedAs();
            if (opened != null) {
                alertWriter.open(alerts.draft(opened, route)).ifPresent(alert -> {
                    events.add(alerts.openedEvent(opened, alert.audience(), sourceRecordTs, committedAt));
                    events.add(AlertEvents.created(alert, sourceRecordTs, committedAt));
                });
            }
            if (alerts.severity(latest) > trace.alertSeverity()) {
                alertWriter
                        .raiseSeverity(dedupKey, alerts.raisePatch(latest))
                        .ifPresent(alert -> events.add(AlertEvents.updated(alert, sourceRecordTs, committedAt)));
            }
            if (!latest.isOpen()) {
                alertWriter.resolve(dedupKey, alerts.closePatch(latest)).ifPresent(alert -> {
                    events.add(alerts.closedEvent(latest, alert.audience(), sourceRecordTs, committedAt));
                    events.add(AlertEvents.updated(alert, sourceRecordTs, committedAt));
                });
            }
        }
    }

    /**
     * The route fell further behind than {@code max-catch-up}: the cursor jumps (DOC-23 §2.2). It counts and warns
     * only when the skipped time had arrivals; skipping an empty stretch changes no state.
     */
    private void reportSkipped(String routeId, Instant cursor, CatchUp catchUp, ZoneId zone) {
        Instant to = catchUp.cursor();
        boolean hadData = store.hasArrivals(routeId, cursor, to, ServiceDates.covering(cursor, to, zone));
        if (!hadData) {
            log.atDebug()
                    .addKeyValue("detector", Detector.DISRUPTION.tag())
                    .addKeyValue("scope", routeId)
                    .addKeyValue("count", catchUp.skippedPoints())
                    .log("analytics grid points skipped, no source data in them");
            return;
        }
        metrics.skippedTicks(Detector.DISRUPTION, catchUp.skippedPoints());
        log.atWarn()
                .addKeyValue("detector", Detector.DISRUPTION.tag())
                .addKeyValue("scope", routeId)
                .addKeyValue("from", cursor)
                .addKeyValue("to", to)
                .addKeyValue("count", catchUp.skippedPoints())
                .log("analytics grid points skipped");
    }

    private void episodeOpened(DisruptionEpisode episode) {
        disruptionMetrics.episodeOpened();
        log.atInfo()
                .addKeyValue("episodeId", episode.id())
                .addKeyValue("routeId", episode.routeId())
                .addKeyValue("directionId", episode.directionId())
                .addKeyValue("zScore", episode.currentZ())
                .addKeyValue("currentAvgDelaySeconds", episode.currentAvg())
                .addKeyValue("baselineMeanSeconds", episode.baselineMean())
                .log("disruption episode opened");
    }

    private void episodeClosed(DisruptionEpisode episode) {
        if (episode.closeReason() != null) {
            disruptionMetrics.episodeClosed(episode.closeReason());
        }
        log.atInfo()
                .addKeyValue("episodeId", episode.id())
                .addKeyValue("routeId", episode.routeId())
                .addKeyValue("directionId", episode.directionId())
                .addKeyValue("zScore", episode.currentZ())
                .addKeyValue("currentAvgDelaySeconds", episode.currentAvg())
                .addKeyValue("baselineMeanSeconds", episode.baselineMean())
                .addKeyValue("closeReason", episode.closeReason())
                .log("disruption episode closed");
    }

    /** At most one warning a minute while no feed is ACTIVE (DOC-23 §15). */
    private void warnNoFeed() {
        Instant now = clock.instant();
        Instant previous = lastNoFeedWarning.get();
        if ((previous == null || !previous.plus(NO_FEED_WARNING_INTERVAL).isAfter(now))
                && lastNoFeedWarning.compareAndSet(previous, now)) {
            log.warn("No GTFS feed is ACTIVE; disruption detection waits for one");
        }
    }

    /**
     * The episodes a run touched, folded from the changes of its buckets: an episode that opens and closes inside one
     * run is one trace, and so is one that was open before the run and is still open after it.
     */
    private static final class Episodes {

        private final Map<UUID, Trace> traces = new LinkedHashMap<>();
        private final ToIntFunction<DisruptionEpisode> severity;

        /** @param severity the alert severity that an episode has in its current state */
        Episodes(ToIntFunction<DisruptionEpisode> severity) {
            this.severity = severity;
        }

        /** An episode that was open before the run; its alert has the severity that its peak gives. */
        void loaded(DisruptionEpisode episode) {
            traces.put(episode.id(), new Trace(null, episode, severity.applyAsInt(episode), false));
        }

        void apply(EpisodeChange change) {
            DisruptionEpisode episode = change.episode();
            Trace known = traces.get(episode.id());
            if (change.kind() == EpisodeChange.Kind.OPENED) {
                // The alert of a new episode starts at the severity of its first bucket.
                traces.put(episode.id(), new Trace(episode, episode, severity.applyAsInt(episode), true));
            } else if (known == null) {
                traces.put(episode.id(), new Trace(null, episode, severity.applyAsInt(episode), true));
            } else {
                traces.put(episode.id(), new Trace(known.openedAs(), episode, known.alertSeverity(), true));
            }
        }

        /** Opened by the run, as they were in their first bucket. */
        List<DisruptionEpisode> opened() {
            return traces.values().stream()
                    .map(Trace::openedAs)
                    .filter(Objects::nonNull)
                    .toList();
        }

        /** Closed by the run, as they were in their last bucket. */
        List<DisruptionEpisode> closed() {
            return traces.values().stream()
                    .filter(Trace::touched)
                    .map(Trace::latest)
                    .filter(episode -> !episode.isOpen())
                    .toList();
        }

        /** Episodes that stayed open but took new values. */
        int updatedCount() {
            return (int) traces.values().stream()
                    .filter(trace -> trace.touched()
                            && trace.openedAs() == null
                            && trace.latest().isOpen())
                    .count();
        }

        List<Trace> touched() {
            return traces.values().stream().filter(Trace::touched).toList();
        }

        /**
         * @param openedAs the episode as it opened, or {@code null} when it was open before the run
         * @param alertSeverity the severity of the alert before the run raised it: what {@code latest} must exceed for
         *     a raise
         */
        private record Trace(
                @Nullable DisruptionEpisode openedAs, DisruptionEpisode latest, int alertSeverity, boolean touched) {}
    }
}
