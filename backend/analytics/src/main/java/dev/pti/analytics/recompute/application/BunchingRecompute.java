package dev.pti.analytics.recompute.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.analytics.bunching.application.ReferenceBunchingSchedule;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter.Previous;
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
import dev.pti.analytics.bunching.domain.VehicleTrack;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.EventTimeGrid;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.recompute.application.port.BunchingRecomputeStore;
import dev.pti.analytics.recompute.application.port.BunchingRecomputeStore.Coverage;
import dev.pti.analytics.recompute.application.port.RecomputeScopes;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.StoredEpisode;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Bunching recompute of one route (DOC-23 §11.3). It walks the 15-second grid with an empty pair state from a warm-up
 * before the start of the range, with the same evaluator and state machine as the live detector, and merges what it
 * finds with the stored episodes (§11.2). Three rules keep the result equal to the live one:
 *
 * <ul>
 *   <li>the start steps back until no stored episode is cut in half: such an episode is rewritten whole, with its id;
 *   <li>the first {@code warm} of the walk only fill the state: after it every pair counts as the live detector counted
 *       it, so an episode is written only if it starts at or after the start;
 *   <li>the walk goes on past the range until nothing is pending, or to the live cursor, where the pair state is
 *       handed over and the live detector continues from the same point.
 * </ul>
 *
 * <p>The whole item is one transaction under the route's advisory lock, which the live {@code advance} only tries: it
 * skips the route meanwhile and catches up from its cursor afterwards.
 */
public final class BunchingRecompute implements DetectorRecompute {

    /** DOC-23 §12.1. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(90);

    /** DOC-23 §2.5: how long the item waits for the route's lock. */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    /** Positions are read in slices of this much event time (DOC-23 §11.3). */
    private static final Duration SLICE = Duration.ofHours(1);

    private final BunchingThresholds thresholds;
    private final BunchingStateStore state;
    private final VehicleHistoryReader history;
    private final BunchingEpisodeWriter episodes;
    private final AlertWriter alerts;
    private final BunchingRecomputeStore store;
    private final RecomputeScopes scopes;
    private final AnalyticsReferenceCache reference;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final TransactionRunner tx;
    private final RunReporter reporter;
    private final BusinessClock clock;
    private final BunchingEvaluator evaluator;

    public BunchingRecompute(
            BunchingThresholds thresholds,
            BunchingStateStore state,
            VehicleHistoryReader history,
            BunchingEpisodeWriter episodes,
            AlertWriter alerts,
            BunchingRecomputeStore store,
            RecomputeScopes scopes,
            AnalyticsReferenceCache reference,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            RunReporter reporter,
            BusinessClock clock) {
        this.thresholds = thresholds;
        this.state = state;
        this.history = history;
        this.episodes = episodes;
        this.alerts = alerts;
        this.store = store;
        this.scopes = scopes;
        this.reference = reference;
        this.lock = lock;
        this.limits = limits;
        this.tx = tx;
        this.reporter = reporter;
        this.clock = clock;
        this.evaluator = new BunchingEvaluator(thresholds, new ReferenceBunchingSchedule(reference));
    }

    @Override
    public Detector detector() {
        return Detector.BUNCHING;
    }

    /** One item per route that has positions in the range or an episode touching it, of an evaluated route type. */
    @Override
    public List<WorkItem> plan(Instant from, Instant to) {
        if (!reference.hasActiveFeed()) {
            return List.of();
        }
        DateRange dates = ServiceDates.covering(from, to, reference.agencyZone());
        Set<String> routes = new TreeSet<>(scopes.routesWithPositions(from, to, dates));
        routes.addAll(scopes.routesWithBunchingEpisodes(from, to));
        return routes.stream()
                .filter(this::evaluated)
                .map(route -> new WorkItem(Detector.BUNCHING, route, from, to))
                .toList();
    }

    @Override
    public DetectorStats execute(WorkItem item, UUID batchId) {
        String routeId = item.scope();
        RunResult result = reporter.report(Detector.BUNCHING, routeId, Trigger.RECOMPUTE, batchId, () -> {
            if (!reference.hasActiveFeed()) {
                return RunResult.noop(Detector.BUNCHING, routeId, Trigger.RECOMPUTE, batchId);
            }
            return tx.inTransaction(() -> recompute(item, batchId));
        });
        return RunStats.of(result);
    }

    private boolean evaluated(String routeId) {
        return reference
                .route(routeId)
                .map(route -> thresholds.routeTypes().contains(route.routeType()))
                .orElse(false);
    }

    private RunResult recompute(WorkItem item, UUID batchId) {
        String routeId = item.scope();
        limits.statementTimeout(STATEMENT_TIMEOUT);
        lock.acquire(LockNames.bunching(routeId), LOCK_TIMEOUT);
        Duration interval = thresholds.evaluationInterval();
        ZoneId zone = reference.agencyZone();
        Instant start = startOfRecompute(routeId, EventTimeGrid.floorGrid(item.from(), interval), interval);
        Optional<Instant> live = state.cursor(routeId);
        Walk walk = new Walk(routeId, start, EventTimeGrid.ceilGrid(item.to(), interval), live.orElse(null), zone);
        walk.run();
        Instant last = walk.last;
        if (last == null) {
            return new RunResult(
                    Detector.BUNCHING, routeId, Trigger.RECOMPUTE, Outcome.OK, batchId, 0, 0, 0, 0, 0, List.of());
        }
        return merge(routeId, start, walk, batchId);
    }

    /**
     * DOC-23 §11.3: the start steps back to the start of the earliest stored episode that it would cut in half, and
     * again, because that episode's start may cut another one.
     */
    private Instant startOfRecompute(String routeId, Instant from, Duration interval) {
        Instant start = from;
        while (true) {
            List<StoredEpisode> reaching = store.episodesReaching(routeId, start);
            if (reaching.isEmpty()) {
                return start;
            }
            Instant earliest = reaching.stream()
                    .map(StoredEpisode::start)
                    .min(Instant::compareTo)
                    .orElseThrow();
            start = EventTimeGrid.floorGrid(earliest, interval);
        }
    }

    /** DOC-23 §11.2: upsert what the walk produced, delete what the walk did not reproduce, hand over the state. */
    private RunResult merge(String routeId, Instant start, Walk walk, UUID batchId) {
        Instant last = walk.last;
        List<EpisodeChangeLog.Entry> produced = walk.changes.entries().stream()
                .filter(entry -> !entry.latest().episodeStart().isBefore(start)
                        || (walk.handover && entry.latest().isOpen()))
                .toList();
        Optional<RouteInfo> route = reference.route(routeId);
        String routeLabel = route.map(RouteInfo::label).orElse(routeId);
        Set<UUID> producedIds = new HashSet<>();
        int inserted = 0;
        int updated = 0;
        int closed = 0;
        for (EpisodeChangeLog.Entry entry : produced) {
            BunchingEpisode latest = entry.latest();
            producedIds.add(latest.id());
            if (episodes.upsert(latest, batchId) == Previous.ABSENT) {
                inserted++;
            } else {
                updated++;
            }
            if (latest.isOpen()) {
                // Open at the handover: the live detector will close it and needs the alert to update.
                BunchingEpisode opened = entry.opened() == null ? latest : entry.opened();
                String directionLabel = route.map(r -> r.directionLabel(opened.directionId()))
                        .orElse("Direction " + opened.directionId());
                alerts.open(BunchingMessages.alert(opened, routeLabel, directionLabel));
            } else {
                closed++;
                alerts.resolve(AlertType.BUNCHING.dedupKey(latest.id()), BunchingMessages.closePatch(latest));
            }
        }
        int deleted = 0;
        for (StoredEpisode stored : store.episodesStartingBetween(routeId, start, last)) {
            if (!producedIds.contains(stored.id())) {
                store.deleteEpisode(stored.id());
                alerts.withdraw(AlertType.BUNCHING.dedupKey(stored.id()));
                deleted++;
            }
        }
        if (walk.handover) {
            StoredState stored = state.load(routeId);
            state.save(
                    routeId,
                    PairStateChanges.between(
                            stored.pairStates(), walk.machine.states().values()));
            if (walk.live == null) {
                // There was no cursor: the live detector continues from the point the walk reached.
                state.saveCursor(routeId, last);
            }
        }
        return new RunResult(
                Detector.BUNCHING,
                routeId,
                Trigger.RECOMPUTE,
                Outcome.OK,
                batchId,
                walk.gridPoints,
                inserted,
                updated,
                closed,
                deleted,
                List.of());
    }

    /** The walk over the grid of one route: its state, the positions in memory and the rules that end it. */
    private final class Walk {

        final PairStateMachine machine;
        final EpisodeChangeLog changes = new EpisodeChangeLog();
        final @Nullable Instant live;

        @Nullable
        Instant last;

        int gridPoints;
        boolean handover;

        private final String routeId;
        private final Instant start;
        private final Instant target;
        private final ZoneId zone;
        private final Duration interval = thresholds.evaluationInterval();
        private final Duration warm;
        private final Instant limit;
        private final @Nullable Instant pendingFrom;

        private List<VehicleTrack> tracks = List.of();
        private @Nullable Instant loadedTo;
        private @Nullable Instant busyUntil;

        /**
         * @param start the first grid point whose episodes are written
         * @param rangeEnd the end of the range, on the grid
         * @param live the cursor of the live detector, if it has one
         */
        Walk(String routeId, Instant start, Instant rangeEnd, @Nullable Instant live, ZoneId zone) {
            this.routeId = routeId;
            this.start = start;
            this.live = live;
            this.zone = zone;
            this.machine = new PairStateMachine(routeId, thresholds);
            // Positions older than position-max-age are not active, and open-consecutive evaluations settle a count.
            this.warm = thresholds.positionMaxAge().plus(interval.multipliedBy(thresholds.openConsecutive()));
            this.target = rangeEnd.plus(warm);
            this.limit = limit(routeId, zone);
            this.pendingFrom = store.earliestPendingPair(routeId).orElse(null);
        }

        /** The walk never goes past what the live detector would have evaluated by now. */
        private Instant limit(String routeId, ZoneId zone) {
            Instant now = clock.instant();
            LocalDate today = now.atZone(zone).toLocalDate();
            Instant newest = history.newestEventTime(routeId, new DateRange(today.minusDays(1), today))
                    .orElse(null);
            Instant watermark =
                    EventTimeGrid.watermark(now, newest, thresholds.allowedLateness(), thresholds.idleTimeout());
            return EventTimeGrid.floorGrid(watermark, interval);
        }

        void run() {
            Instant tick = EventTimeGrid.floorGrid(start.minus(warm), interval);
            boolean stopped = false;
            while (!stopped && !tick.isAfter(limit)) {
                changes.add(machine.apply(evaluator.evaluate(routeId, tick, tracksAt(tick))));
                last = tick;
                gridPoints++;
                if (live != null && tick.equals(live)) {
                    handover = true;
                    stopped = true;
                } else if (!tick.isBefore(target) && machine.states().isEmpty() && quiescent(tick)) {
                    stopped = true;
                } else {
                    tick = tick.plus(interval);
                }
            }
            if (!stopped && last != null && live == null && !machine.states().isEmpty()) {
                // Out of data to evaluate with a state still open and nobody to take it over.
                handover = true;
            }
        }

        /** The positions up to {@code tick}, read in slices; each slice reaches back far enough for its first tick. */
        private List<VehicleTrack> tracksAt(Instant tick) {
            if (loadedTo == null || tick.isAfter(loadedTo)) {
                Instant upTo = tick.plus(SLICE).isAfter(limit) ? limit : tick.plus(SLICE);
                Instant after = tick.minus(thresholds.leaderLookback()).minus(thresholds.positionMaxAge());
                tracks = VehicleTrack.group(
                        history.positions(routeId, ServiceDates.covering(after, upTo, zone), after, upTo));
                loadedTo = upTo;
            }
            return tracks;
        }

        /**
         * DOC-23 §11.3: nothing is pending at {@code tick}: no stored episode covers it, and the live detector is not
         * counting a pair that began by then. The state of the walk was checked by the caller.
         */
        private boolean quiescent(Instant tick) {
            if (pendingFrom != null && !tick.isBefore(pendingFrom)) {
                return false;
            }
            if (busyUntil != null && !tick.isAfter(busyUntil)) {
                return false;
            }
            Optional<Coverage> covering = store.coverageAt(routeId, tick);
            if (covering.isEmpty()) {
                return true;
            }
            Coverage coverage = covering.get();
            busyUntil = coverage.open() || coverage.latestEnd() == null ? Instant.MAX : coverage.latestEnd();
            return false;
        }
    }
}
