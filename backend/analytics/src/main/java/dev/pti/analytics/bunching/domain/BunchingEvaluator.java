package dev.pti.analytics.bunching.domain;

import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.TripPattern;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The evaluation of a route at one grid point (DOC-23 §5.2 to §5.4). For each active vehicle, as a follower, it finds
 * the vehicle ahead that passed the follower's next stop last, and the gap between the two. It reads only positions
 * with an event time up to the grid point, so the result does not depend on how the data arrived (DOC-23 §2.2).
 *
 * <p>Pure: the positions come in as {@link VehicleTrack}s and the schedule as a {@link BunchingSchedule}.
 */
public final class BunchingEvaluator {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final BunchingThresholds thresholds;
    private final BunchingSchedule schedule;

    public BunchingEvaluator(BunchingThresholds thresholds, BunchingSchedule schedule) {
        this.thresholds = thresholds;
        this.schedule = schedule;
    }

    /**
     * @param tracks the positions of every vehicle of the route in the loaded window; both directions
     */
    public TickEvaluation evaluate(String routeId, Instant tick, List<VehicleTrack> tracks) {
        Instant activeAfter = tick.minus(thresholds.positionMaxAge());
        Instant lookbackFrom = tick.minus(thresholds.leaderLookback());
        Map<String, ActiveVehicle> active = new LinkedHashMap<>();
        for (VehicleTrack track : tracks) {
            track.newest(activeAfter, tick)
                    .ifPresent(newest -> active.put(
                            track.vehicleId(), new ActiveVehicle(track, newest, schedule.trip(newest.tripId()))));
        }
        List<Evaluation> evaluations = new ArrayList<>();
        Map<String, SkipReason> skipped = new LinkedHashMap<>();
        for (ActiveVehicle follower : active.values()) {
            SkipReason reason = evaluateFollower(routeId, tick, lookbackFrom, follower, active, evaluations);
            if (reason != null) {
                skipped.put(follower.id(), reason);
            }
        }
        Map<String, VehiclePosition> positions = new LinkedHashMap<>();
        active.forEach((id, vehicle) -> positions.put(id, vehicle.newest()));
        return new TickEvaluation(tick, evaluations, positions, skipped);
    }

    /** Adds the evaluation of {@code follower} to {@code evaluations}, or returns why there is none. */
    private @Nullable SkipReason evaluateFollower(
            String routeId,
            Instant tick,
            Instant lookbackFrom,
            ActiveVehicle follower,
            Map<String, ActiveVehicle> active,
            List<Evaluation> evaluations) {
        TripPattern pattern = follower.pattern();
        if (pattern == null || follower.index() < 0) {
            return SkipReason.UNKNOWN_TRIP;
        }
        List<PatternStop> stops = pattern.stops();
        int index = follower.index();
        if (index < thresholds.excludeFirstStops()) {
            return SkipReason.FIRST_STOPS;
        }
        if (index > stops.size() - 1 - thresholds.excludeLastStops()) {
            return SkipReason.LAST_STOPS;
        }
        VehiclePosition position = follower.newest();
        OptionalInt headway = schedule.scheduledHeadway(routeId, position.directionId(), position.serviceDate(), tick);
        if (headway.isEmpty()) {
            return SkipReason.NO_HEADWAY;
        }
        if (headway.getAsInt() > thresholds.maxHeadway().getSeconds()) {
            return SkipReason.HEADWAY_TOO_LONG;
        }
        String stopId = stops.get(index).stopId();
        ActiveVehicle leader = null;
        Passage best = null;
        // Vehicles come in id order and only a strictly later passage replaces the best one: ties go to the smaller id.
        for (ActiveVehicle candidate : active.values()) {
            if (candidate == follower || candidate.newest().directionId() != position.directionId()) {
                continue;
            }
            TripPattern candidatePattern = candidate.pattern();
            if (candidatePattern == null || candidate.index() < 0) {
                continue;
            }
            if (candidate.index() > candidatePattern.stops().size() - 1 - thresholds.excludeLastStops()) {
                continue;
            }
            Passage passage = passage(candidate, stopId, tick, lookbackFrom);
            if (passage != null && (best == null || passage.time().isAfter(best.time()))) {
                best = passage;
                leader = candidate;
            }
        }
        if (leader == null || best == null) {
            return SkipReason.NO_LEADER;
        }
        double remaining = remainingSeconds(pattern, index, position);
        double gap = Duration.between(best.time(), position.eventTimestamp()).toNanos() / NANOS_PER_SECOND + remaining;
        evaluations.add(new Evaluation(
                leader.id(),
                follower.id(),
                leader.newest().tripId(),
                position.tripId(),
                position.directionId(),
                stopId,
                Math.toIntExact(Math.round(gap)),
                headway.getAsInt(),
                best.source()));
        return null;
    }

    /** The time the schedule still needs to bring the follower to its next stop: zero while it stands there. */
    private static double remainingSeconds(TripPattern pattern, int index, VehiclePosition position) {
        if (position.status() == StopStatus.STOPPED_AT) {
            return 0;
        }
        double progress = TripGeometry.progress(pattern, index, position);
        double arrival = pattern.stops().get(index).arrivalSeconds();
        return Math.max(0, arrival - TripGeometry.scheduledSeconds(pattern, progress));
    }

    /**
     * When {@code vehicle} passed the stop {@code stopId} on its current trip (DOC-23 §5.3), or {@code null} when it
     * has not, or passed it before the look-back.
     */
    private @Nullable Passage passage(ActiveVehicle vehicle, String stopId, Instant tick, Instant lookbackFrom) {
        TripPattern pattern = vehicle.pattern();
        if (pattern == null) {
            return null;
        }
        List<PatternStop> stops = pattern.stops();
        List<Integer> stopIndexes = indexesOf(stops, stopId);
        if (stopIndexes.isEmpty()) {
            return null;
        }
        List<Located> history = vehicle.history(lookbackFrom, tick);
        Located reached = null;
        int reachedAt = -1;
        int stopIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            Located u = history.get(i);
            int j = largestAtMost(stopIndexes, u.index());
            boolean passed =
                    j >= 0 && (u.index() > j || (u.index() == j && u.position().status() == StopStatus.STOPPED_AT));
            if (passed) {
                reached = u;
                reachedAt = i;
                stopIndex = j;
                break;
            }
        }
        if (reached == null) {
            return null;
        }
        Instant time;
        PassSource source;
        if (reached.position().status() == StopStatus.STOPPED_AT && reached.index() == stopIndex) {
            time = reached.position().eventTimestamp();
            source = PassSource.OBSERVED;
        } else {
            Located before = reachedAt > 0 ? history.get(reachedAt - 1) : null;
            double target = stops.get(stopIndex).dist();
            if (before != null && reached.progress() > before.progress()) {
                double ratio = clamp((target - before.progress()) / (reached.progress() - before.progress()), 0, 1);
                long span = Duration.between(
                                before.position().eventTimestamp(),
                                reached.position().eventTimestamp())
                        .toNanos();
                time = before.position().eventTimestamp().plusNanos(Math.round(ratio * span));
                source = PassSource.OBSERVED;
            } else {
                double back = Math.max(
                        0,
                        TripGeometry.scheduledSeconds(pattern, reached.progress())
                                - TripGeometry.scheduledSeconds(pattern, target));
                time = reached.position().eventTimestamp().minusNanos(Math.round(back * NANOS_PER_SECOND));
                source = PassSource.ESTIMATED;
            }
        }
        return time.isBefore(lookbackFrom) ? null : new Passage(time, source);
    }

    private static List<Integer> indexesOf(List<PatternStop> stops, String stopId) {
        List<Integer> indexes = new ArrayList<>(1);
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).stopId().equals(stopId)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    /** The largest value of the ascending list that is at most {@code limit}, or -1. */
    private static int largestAtMost(List<Integer> ascending, int limit) {
        for (int i = ascending.size() - 1; i >= 0; i--) {
            if (ascending.get(i) <= limit) {
                return ascending.get(i);
            }
        }
        return -1;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Passage(Instant time, PassSource source) {}

    /** A position of a vehicle on its trip with the stop index and progress worked out. */
    private record Located(VehiclePosition position, int index, double progress) {}

    /** An active vehicle: its newest position, the pattern of that trip and, when needed, its recent positions. */
    private static final class ActiveVehicle {

        private final VehicleTrack track;
        private final VehiclePosition newest;
        private final @Nullable TripPattern pattern;
        private final int index;
        private @Nullable List<Located> history;

        ActiveVehicle(VehicleTrack track, VehiclePosition newest, Optional<TripPattern> pattern) {
            this.track = track;
            this.newest = newest;
            this.pattern = pattern.orElse(null);
            this.index = this.pattern == null ? -1 : this.pattern.indexOfSequence(newest.currentStopSequence());
        }

        String id() {
            return track.vehicleId();
        }

        VehiclePosition newest() {
            return newest;
        }

        @Nullable
        TripPattern pattern() {
            return pattern;
        }

        /** Index in the pattern of the stop the newest position is at or heading to; -1 when it cannot be placed. */
        int index() {
            return index;
        }

        /** The positions on the newest position's trip in {@code [from, to]} that can be placed on the pattern. */
        List<Located> history(Instant from, Instant to) {
            if (history == null) {
                TripPattern trip = pattern;
                List<Located> located = new ArrayList<>();
                if (trip != null) {
                    for (VehiclePosition p : track.history(newest.tripId(), from, to)) {
                        int i = trip.indexOfSequence(p.currentStopSequence());
                        if (i >= 0) {
                            located.add(new Located(p, i, TripGeometry.progress(trip, i, p)));
                        }
                    }
                }
                history = located;
            }
            return history;
        }
    }
}
