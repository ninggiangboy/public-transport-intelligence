package dev.pti.analytics.disruption.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertRecord;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.disruption.application.port.DisruptionMetrics;
import dev.pti.analytics.disruption.application.port.DisruptionStore;
import dev.pti.analytics.disruption.domain.Arrival;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.CloseReason;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.events.Audience;
import dev.pti.common.time.DayType;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory stand-ins for the ports of the disruption detector. */
final class DisruptionFakes {

    private DisruptionFakes() {}

    /** The warehouse: arrivals, state, snapshots and episodes of one or more routes. */
    static final class Store implements DisruptionStore {

        final List<Arrival> arrivals = new ArrayList<>();
        final Map<String, Map<Integer, BaselineState>> baselines = new HashMap<>();
        final Map<String, TreeMap<Instant, Map<Integer, BaselineState>>> snapshots = new HashMap<>();
        final Map<UUID, DisruptionEpisode> episodes = new LinkedHashMap<>();
        final List<UUID> batches = new ArrayList<>();
        int episodeWrites;

        @Override
        public Optional<Instant> cursor(String routeId) {
            return baselines.getOrDefault(routeId, Map.of()).values().stream()
                    .map(BaselineState::lastBucket)
                    .min(Instant::compareTo);
        }

        @Override
        public Optional<Instant> newestUpdate(String routeId, DateRange serviceDates) {
            return arrivals.stream().map(Arrival::observedAt).max(Instant::compareTo);
        }

        @Override
        public Map<Integer, BaselineState> baselines(String routeId) {
            return Map.copyOf(baselines.getOrDefault(routeId, Map.of()));
        }

        @Override
        public Optional<DisruptionEpisode> openEpisode(UUID id) {
            return Optional.ofNullable(episodes.get(id)).filter(DisruptionEpisode::isOpen);
        }

        @Override
        public List<Arrival> arrivals(String routeId, Instant from, Instant to, DateRange serviceDates) {
            return arrivals.stream()
                    .filter(a ->
                            !a.observedAt().isBefore(from) && a.observedAt().isBefore(to))
                    .toList();
        }

        @Override
        public boolean hasArrivals(String routeId, Instant from, Instant to, DateRange serviceDates) {
            return !arrivals(routeId, from, to, serviceDates).isEmpty();
        }

        @Override
        public void saveBaseline(String routeId, int directionId, BaselineState state) {
            baselines.computeIfAbsent(routeId, r -> new TreeMap<>()).put(directionId, state);
        }

        @Override
        public void saveSnapshot(Instant snapshotHour, String routeId, int directionId, BaselineState state) {
            snapshots
                    .computeIfAbsent(routeId, r -> new TreeMap<>())
                    .computeIfAbsent(snapshotHour, h -> new TreeMap<>())
                    .put(directionId, state);
        }

        @Override
        public void saveEpisode(DisruptionEpisode episode, UUID batchId) {
            episodes.put(episode.id(), episode);
            batches.add(batchId);
            episodeWrites++;
        }

        @Override
        public List<String> routesNeedingTick() {
            return baselines.entrySet().stream()
                    .filter(e -> e.getValue().values().stream()
                            .anyMatch(s ->
                                    s.openEpisodeId() != null || s.consecutiveHigh() > 0 || s.consecutiveLow() > 0))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }

        void arrive(int direction, String stop, Instant at, int delay) {
            arrivals.add(new Arrival(direction, stop, at, delay));
        }
    }

    /** {@code ops.alert_event}: dedup, resolve and raise as the SQL of DOC-23 §10.2 does. */
    static final class Alerts implements AlertWriter {

        final Map<String, AlertRecord> byKey = new LinkedHashMap<>();
        private final Instant createdAt = Instant.parse("2026-09-29T00:00:00Z");

        @Override
        public Optional<AlertRecord> open(AlertDraft draft) {
            if (byKey.containsKey(draft.dedupKey())) {
                return Optional.empty();
            }
            AlertRecord record = new AlertRecord(
                    draft.id(),
                    draft.type(),
                    draft.severity(),
                    draft.audience(),
                    draft.routeId(),
                    draft.refTable(),
                    draft.refId(),
                    draft.title(),
                    draft.body(),
                    createdAt,
                    null);
            byKey.put(draft.dedupKey(), record);
            return Optional.of(record);
        }

        @Override
        public Optional<AlertRecord> resolve(String dedupKey, Map<String, Object> bodyPatch) {
            AlertRecord record = byKey.get(dedupKey);
            if (record == null || record.resolvedAt() != null) {
                return Optional.empty();
            }
            AlertRecord resolved = copy(record, record.severity(), record.audience(), bodyPatch, createdAt);
            byKey.put(dedupKey, resolved);
            return Optional.of(resolved);
        }

        @Override
        public Optional<AlertRecord> raiseSeverity(String dedupKey, Map<String, Object> bodyPatch) {
            AlertRecord record = byKey.get(dedupKey);
            if (record == null || record.severity() >= 2 || record.resolvedAt() != null) {
                return Optional.empty();
            }
            AlertRecord raised = copy(record, 2, record.audience(), bodyPatch, null);
            byKey.put(dedupKey, raised);
            return Optional.of(raised);
        }

        @Override
        public boolean withdraw(String dedupKey) {
            throw new UnsupportedOperationException("The detector never withdraws; only a recompute does");
        }

        /** Triage narrows the audience after the alert was opened (FR-09.5). */
        void narrowTo(String dedupKey, Audience audience) {
            AlertRecord record = byKey.get(dedupKey);
            byKey.put(dedupKey, copy(record, record.severity(), audience, Map.of(), record.resolvedAt()));
        }

        private static AlertRecord copy(
                AlertRecord record, int severity, Audience audience, Map<String, Object> patch, Instant resolvedAt) {
            Map<String, Object> body = new LinkedHashMap<>(record.body());
            body.putAll(patch);
            return new AlertRecord(
                    record.id(),
                    record.type(),
                    severity,
                    audience,
                    record.routeId(),
                    record.refTable(),
                    record.refId(),
                    record.title(),
                    body,
                    record.createdAt(),
                    resolvedAt);
        }
    }

    /** An advisory lock that can be taken by someone else. */
    static final class Lock implements AdvisoryLock {

        boolean heldElsewhere;
        final List<String> names = new ArrayList<>();

        @Override
        public boolean tryAcquire(String lockName) {
            names.add(lockName);
            return !heldElsewhere;
        }

        @Override
        public void acquire(String lockName, Duration lockTimeout) {
            names.add(lockName);
        }
    }

    static final class NoLimits implements TransactionLimits {

        Duration statementTimeout;

        @Override
        public void statementTimeout(Duration timeout) {
            statementTimeout = timeout;
        }
    }

    /** Runs the work at once and counts the transactions. */
    static final class Transactions implements TransactionRunner {

        int newTransactions;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            newTransactions++;
            return work.get();
        }
    }

    static final class Metrics implements AnalyticsMetrics {

        long skippedTicks;

        @Override
        public void run(Detector detector, Trigger trigger, Outcome outcome) {}

        @Override
        public void runDuration(Detector detector, Duration duration) {}

        @Override
        public void dispatchDelay(Duration delay) {}

        @Override
        public void lateBatch(Detector detector) {}

        @Override
        public void skippedTicks(Detector detector, long count) {
            skippedTicks += count;
        }

        @Override
        public void openEpisodes(Detector detector, long count) {}

        @Override
        public void dropped() {}
    }

    static final class EpisodeMetrics implements DisruptionMetrics {

        int opened;
        final List<CloseReason> closed = new ArrayList<>();

        @Override
        public void episodeOpened() {
            opened++;
        }

        @Override
        public void episodeClosed(CloseReason reason) {
            closed.add(reason);
        }
    }

    /** A feed with routes by id; route type 3 is a bus. */
    static final class Reference implements AnalyticsReferenceCache {

        boolean active = true;
        final Map<String, RouteInfo> routes = new HashMap<>();

        @Override
        public boolean hasActiveFeed() {
            return active;
        }

        @Override
        public long feedVersionId() {
            return 1;
        }

        @Override
        public ZoneId agencyZone() {
            return ZoneId.of("America/Chicago");
        }

        @Override
        public Optional<RouteInfo> route(String routeId) {
            return Optional.ofNullable(routes.get(routeId));
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return Optional.empty();
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hourOfServiceDay) {
            return OptionalInt.empty();
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.of(serviceDate);
        }
    }
}
