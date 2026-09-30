package dev.pti.analytics.bunching.application;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertRecord;
import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.application.port.BunchingStateStore;
import dev.pti.analytics.bunching.application.port.VehicleHistoryReader;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.PairState;
import dev.pti.analytics.bunching.domain.PairStateChanges;
import dev.pti.analytics.bunching.domain.VehiclePosition;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.DayType;
import dev.pti.common.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** Hand-written stand-ins for the ports of the bunching use case, kept in memory (DOC-49 §8). */
final class InMemoryBunching {

    private InMemoryBunching() {}

    /** The store and the episode writer over the same maps, like the two adapters over the same tables. */
    static class Store implements BunchingStateStore, BunchingEpisodeWriter {

        final Map<String, Instant> cursors = new HashMap<>();
        final Map<String, Map<String, PairState>> states = new HashMap<>();
        final Map<UUID, BunchingEpisode> episodes = new LinkedHashMap<>();
        final Map<UUID, UUID> batchOfEpisode = new HashMap<>();
        int episodeWrites;

        @Override
        public Optional<Instant> cursor(String routeId) {
            return Optional.ofNullable(cursors.get(routeId));
        }

        @Override
        public void saveCursor(String routeId, Instant lastTick) {
            cursors.put(routeId, lastTick);
        }

        @Override
        public StoredState load(String routeId) {
            List<PairState> route =
                    List.copyOf(states.getOrDefault(routeId, Map.of()).values());
            List<BunchingEpisode> open = route.stream()
                    .map(PairState::openEpisodeId)
                    .filter(id -> id != null
                            && episodes.containsKey(id)
                            && episodes.get(id).isOpen())
                    .map(episodes::get)
                    .toList();
            return new StoredState(route, open);
        }

        @Override
        public void save(String routeId, PairStateChanges changes) {
            Map<String, PairState> route = states.computeIfAbsent(routeId, r -> new LinkedHashMap<>());
            changes.deletes().forEach(s -> route.remove(rowKey(s)));
            changes.upserts().forEach(s -> route.put(rowKey(s), s));
        }

        @Override
        public List<String> routesNeedingTick() {
            Set<String> routes = new TreeSet<>();
            states.forEach((route, rows) -> {
                if (!rows.isEmpty()) {
                    routes.add(route);
                }
            });
            episodes.values().stream().filter(BunchingEpisode::isOpen).forEach(e -> routes.add(e.routeId()));
            return List.copyOf(routes);
        }

        @Override
        public Previous upsert(BunchingEpisode episode, UUID batchId) {
            BunchingEpisode before = episodes.put(episode.id(), episode);
            batchOfEpisode.put(episode.id(), batchId);
            episodeWrites++;
            if (before == null) {
                return Previous.ABSENT;
            }
            return before.isOpen() ? Previous.OPEN : Previous.CLOSED;
        }

        /** What an operator does to rerun the detector over the same data: the cursor and the states go, rows stay. */
        void resetCursorAndStates(String routeId) {
            cursors.remove(routeId);
            states.remove(routeId);
        }

        List<PairState> pairStates(String routeId) {
            return List.copyOf(states.getOrDefault(routeId, Map.of()).values());
        }

        private static String rowKey(PairState s) {
            return s.directionId() + "|" + s.leader() + "|" + s.follower();
        }
    }

    /** The positions of one route, filtered by time like the query. */
    static final class History implements VehicleHistoryReader {

        final List<VehiclePosition> all = new ArrayList<>();

        @Override
        public Optional<Instant> newestEventTime(String routeId, DateRange serviceDates) {
            return all.stream().map(VehiclePosition::eventTimestamp).max(Instant::compareTo);
        }

        @Override
        public boolean anyPositionIn(String routeId, DateRange dates, Instant afterExclusive, Instant upToInclusive) {
            return !positions(routeId, dates, afterExclusive, upToInclusive).isEmpty();
        }

        @Override
        public List<VehiclePosition> positions(
                String routeId, DateRange dates, Instant afterExclusive, Instant upToInclusive) {
            return all.stream()
                    .filter(p -> p.eventTimestamp().isAfter(afterExclusive)
                            && !p.eventTimestamp().isAfter(upToInclusive))
                    .sorted(java.util.Comparator.comparing(VehiclePosition::vehicleId)
                            .thenComparing(VehiclePosition::eventTimestamp))
                    .toList();
        }
    }

    /** {@code ops.alert_event} with its dedup key. */
    static final class Alerts implements AlertWriter {

        final Map<String, AlertRecord> byDedupKey = new LinkedHashMap<>();
        private final Instant now;

        Alerts(Instant now) {
            this.now = now;
        }

        @Override
        public Optional<AlertRecord> open(AlertDraft draft) {
            if (byDedupKey.containsKey(draft.dedupKey())) {
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
                    now,
                    null);
            byDedupKey.put(draft.dedupKey(), record);
            return Optional.of(record);
        }

        @Override
        public Optional<AlertRecord> resolve(String dedupKey, Map<String, Object> bodyPatch) {
            AlertRecord record = byDedupKey.get(dedupKey);
            if (record == null || record.resolvedAt() != null) {
                return Optional.empty();
            }
            Map<String, Object> body = new LinkedHashMap<>(record.body());
            body.putAll(bodyPatch);
            AlertRecord resolved = new AlertRecord(
                    record.id(),
                    record.type(),
                    record.severity(),
                    record.audience(),
                    record.routeId(),
                    record.refTable(),
                    record.refId(),
                    record.title(),
                    body,
                    record.createdAt(),
                    now);
            byDedupKey.put(dedupKey, resolved);
            return Optional.of(resolved);
        }

        @Override
        public Optional<AlertRecord> raiseSeverity(String dedupKey, Map<String, Object> bodyPatch) {
            throw new UnsupportedOperationException("Bunching alerts keep severity 1");
        }
    }

    /** A feed with a few routes, the given trips and one headway for every hour. */
    static final class Reference implements AnalyticsReferenceCache {

        boolean active = true;
        int headwaySeconds = 600;
        final Map<String, RouteInfo> routes = new HashMap<>();
        final Map<String, TripPattern> trips = new HashMap<>();

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
            return Optional.ofNullable(trips.get(tripId));
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hourOfServiceDay) {
            return OptionalInt.of(headwaySeconds);
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.WEEKDAY;
        }
    }

    /** One lock that can be held elsewhere. */
    static final class Lock implements AdvisoryLock {

        boolean heldElsewhere;
        final List<String> taken = new ArrayList<>();

        @Override
        public boolean tryAcquire(String lockName) {
            if (heldElsewhere) {
                return false;
            }
            taken.add(lockName);
            return true;
        }

        @Override
        public void acquire(String lockName, Duration lockTimeout) {
            throw new UnsupportedOperationException("The live path never waits for the lock");
        }
    }

    /** Runs the work inline and counts the transactions it was asked to open. */
    static final class Transactions implements TransactionRunner {

        int opened;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            opened++;
            return work.get();
        }
    }

    /** A clock that tests move by hand. */
    static final class ManualClock extends Clock {

        private Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
