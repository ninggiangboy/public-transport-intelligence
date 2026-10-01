package dev.pti.analytics.recompute.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertType;
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
import dev.pti.analytics.recompute.application.port.DisruptionRecomputeStore;
import dev.pti.analytics.recompute.application.port.RecomputeScopes;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.StoredEpisode;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Disruption recompute of one route, both directions in one transaction (DOC-23 §11.4). The baseline is an EWMA with a
 * long memory, so a change in one minute changes every state after it: the recompute starts from the latest hourly
 * snapshot that has nothing open, replays every bucket up to the live cursor with the same {@link DisruptionReplay}
 * the live detector uses, and replaces the live state. Without a snapshot it starts from an empty state one window
 * before the range, which gives what the live detector gave, because buckets before the first data change nothing.
 */
public final class DisruptionRecompute implements DetectorRecompute {

    /** DOC-23 §12.1. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(90);

    /** DOC-23 §2.5: how long the item waits for the route's lock. */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    /** Arrivals are read in slices of this many buckets, six hours of one-minute buckets (DOC-23 §11.4). */
    private static final int SLICE_BUCKETS = 360;

    private final boolean enabled;
    private final DisruptionThresholds thresholds;
    private final DisruptionStore disruption;
    private final DisruptionRecomputeStore store;
    private final RecomputeScopes scopes;
    private final AnalyticsReferenceCache reference;
    private final AlertWriter alerts;
    private final AdvisoryLock lock;
    private final TransactionLimits limits;
    private final TransactionRunner tx;
    private final RunReporter reporter;
    private final BusinessClock clock;
    private final DisruptionReplay replay;
    private final DisruptionAlerts messages;

    public DisruptionRecompute(
            boolean enabled,
            DisruptionThresholds thresholds,
            DisruptionStore disruption,
            DisruptionRecomputeStore store,
            RecomputeScopes scopes,
            AnalyticsReferenceCache reference,
            AlertWriter alerts,
            AdvisoryLock lock,
            TransactionLimits limits,
            TransactionRunner tx,
            RunReporter reporter,
            BusinessClock clock) {
        this.enabled = enabled;
        this.thresholds = thresholds;
        this.disruption = disruption;
        this.store = store;
        this.scopes = scopes;
        this.reference = reference;
        this.alerts = alerts;
        this.lock = lock;
        this.limits = limits;
        this.tx = tx;
        this.reporter = reporter;
        this.clock = clock;
        this.replay = new DisruptionReplay(thresholds);
        this.messages = new DisruptionAlerts(thresholds.severityHighZ());
    }

    @Override
    public Detector detector() {
        return Detector.DISRUPTION;
    }

    /** One item per route that has trip updates in the range or an episode touching it, of an evaluated route type. */
    @Override
    public List<WorkItem> plan(Instant from, Instant to) {
        if (!enabled || !reference.hasActiveFeed()) {
            return List.of();
        }
        DateRange dates = ServiceDates.covering(from, to, reference.agencyZone());
        Set<String> routes = new TreeSet<>(scopes.routesWithTripUpdates(from, to, dates));
        routes.addAll(scopes.routesWithDisruptionEpisodes(from, to));
        return routes.stream()
                .filter(this::evaluated)
                .map(route -> new WorkItem(Detector.DISRUPTION, route, from, to))
                .toList();
    }

    /**
     * An arrival is observed at or before the {@code event_timestamp} of its row, so the rows of a replay reach one
     * window back in observation time (DOC-23 §11.1).
     */
    @Override
    public List<WorkItem> planReplay(ReplayRange range) {
        return plan(range.minEventTs().minus(thresholds.window()), range.maxEventTs());
    }

    @Override
    public DetectorStats execute(WorkItem item, UUID batchId) {
        String routeId = item.scope();
        RunResult result = reporter.report(Detector.DISRUPTION, routeId, Trigger.RECOMPUTE, batchId, () -> {
            Optional<RouteInfo> route = reference.hasActiveFeed() ? reference.route(routeId) : Optional.empty();
            if (route.isEmpty()) {
                return RunResult.noop(Detector.DISRUPTION, routeId, Trigger.RECOMPUTE, batchId);
            }
            return tx.inTransaction(() -> recompute(item, route.get(), batchId));
        });
        return RunStats.of(result);
    }

    private boolean evaluated(String routeId) {
        return reference
                .route(routeId)
                .map(route -> thresholds.routeTypes().contains(route.routeType()))
                .orElse(false);
    }

    private RunResult recompute(WorkItem item, RouteInfo route, UUID batchId) {
        String routeId = route.routeId();
        limits.statementTimeout(STATEMENT_TIMEOUT);
        lock.acquire(LockNames.disruption(routeId), LOCK_TIMEOUT);
        Duration bucket = thresholds.bucket();
        ZoneId zone = reference.agencyZone();
        Instant latestHour = Instant.ofEpochSecond(Math.floorDiv(item.from().getEpochSecond(), 3600L) * 3600L);
        Map<Integer, DirectionState> states = new TreeMap<>();
        Map<Integer, Instant> starts = new TreeMap<>();
        for (int directionId : DisruptionReplay.DIRECTIONS) {
            DirectionState initial = store.latestSnapshot(routeId, directionId, latestHour)
                    .map(snapshot -> new DirectionState(snapshot, null))
                    .orElseGet(() -> DirectionState.initial(
                            EventTimeGrid.floorGrid(item.from(), bucket).minus(thresholds.window())));
            states.put(directionId, initial);
            starts.put(directionId, initial.baseline().lastBucket());
        }
        Map<Integer, BaselineState> live = disruption.baselines(routeId);
        Instant end = live.isEmpty()
                ? EventTimeGrid.floorGrid(clock.instant().minus(thresholds.idleTimeout()), bucket)
                : live.values().stream()
                        .map(BaselineState::lastBucket)
                        .min(Instant::compareTo)
                        .orElseThrow();
        Instant earliest = starts.values().stream().min(Instant::compareTo).orElseThrow();
        List<Instant> bucketEnds = EventTimeGrid.pointsAfter(earliest, end, bucket);
        Episodes found = new Episodes();
        Map<Integer, DirectionState> current = states;
        for (int from = 0; from < bucketEnds.size(); from += SLICE_BUCKETS) {
            List<Instant> slice = bucketEnds.subList(from, Math.min(from + SLICE_BUCKETS, bucketEnds.size()));
            Instant arrivalsFrom = slice.getFirst().minus(thresholds.window());
            Instant arrivalsTo = slice.getLast();
            List<Arrival> arrivals = disruption.arrivals(
                    routeId, arrivalsFrom, arrivalsTo, ServiceDates.covering(arrivalsFrom, arrivalsTo, zone));
            DisruptionReplay.Result replayed = replay.run(
                    routeId,
                    current,
                    new ArrivalSeries(arrivals, thresholds),
                    slice,
                    (hour, directionId, state) -> disruption.saveSnapshot(hour, routeId, directionId, state));
            current = replayed.states();
            replayed.changes().forEach(found::apply);
        }
        return merge(route, starts, current, found, bucketEnds.size(), batchId);
    }

    /** DOC-23 §11.2: write what the replay produced, delete what it did not reproduce, replace the live state. */
    private RunResult merge(
            RouteInfo route,
            Map<Integer, Instant> starts,
            Map<Integer, DirectionState> finals,
            Episodes found,
            int buckets,
            UUID batchId) {
        String routeId = route.routeId();
        int opened = 0;
        int closed = 0;
        Set<UUID> producedIds = new HashSet<>();
        for (Episodes.Trace trace : found.traces.values()) {
            DisruptionEpisode latest = trace.latest();
            disruption.saveEpisode(latest, batchId);
            producedIds.add(latest.id());
            if (trace.openedAs() != null) {
                opened++;
            }
            String dedupKey = DisruptionAlerts.dedupKey(latest);
            if (latest.isOpen()) {
                // Open at the handover: the live detector will close it and needs the alert to update.
                DisruptionEpisode first = trace.openedAs() == null ? latest : trace.openedAs();
                alerts.open(messages.draft(first, route));
                if (messages.severity(latest) > messages.severity(first)) {
                    alerts.raiseSeverity(dedupKey, messages.raisePatch(latest));
                }
            } else {
                closed++;
                alerts.resolve(dedupKey, messages.closePatch(latest));
            }
        }
        int deleted = 0;
        for (int directionId : DisruptionReplay.DIRECTIONS) {
            Instant start = starts.get(directionId);
            BaselineState state = finals.get(directionId).baseline();
            if (!state.lastBucket().isAfter(start)) {
                continue; // no bucket ran for this direction: its live state and its episodes stay as they are
            }
            for (StoredEpisode stored : store.episodesStartingFrom(routeId, directionId, start)) {
                if (!producedIds.contains(stored.id())) {
                    store.deleteEpisode(stored.id());
                    alerts.withdraw(AlertType.DISRUPTION.dedupKey(stored.id()));
                    deleted++;
                }
            }
            disruption.saveBaseline(routeId, directionId, state);
        }
        return new RunResult(
                Detector.DISRUPTION,
                routeId,
                Trigger.RECOMPUTE,
                Outcome.OK,
                batchId,
                buckets,
                opened,
                found.traces.size() - opened,
                closed,
                deleted,
                List.of());
    }

    /** The episodes of a replay, folded from the changes of its buckets: the first and the last state of each. */
    private static final class Episodes {

        final Map<UUID, Trace> traces = new LinkedHashMap<>();

        void apply(EpisodeChange change) {
            DisruptionEpisode episode = change.episode();
            Trace known = traces.get(episode.id());
            if (change.kind() == EpisodeChange.Kind.OPENED) {
                traces.put(episode.id(), new Trace(episode, episode));
            } else {
                traces.put(episode.id(), new Trace(known == null ? null : known.openedAs(), episode));
            }
        }

        /**
         * @param openedAs the episode as it opened in the replay; {@code null} when it was open before it
         * @param latest the episode after the last bucket
         */
        record Trace(@Nullable DisruptionEpisode openedAs, DisruptionEpisode latest) {}
    }
}
