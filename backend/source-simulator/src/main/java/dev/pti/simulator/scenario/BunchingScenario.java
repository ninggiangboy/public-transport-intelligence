package dev.pti.simulator.scenario;

import dev.pti.simulator.emit.Emitter;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Route;
import dev.pti.simulator.feed.TripSchedule;
import dev.pti.simulator.motion.SegmentOverlay;
import dev.pti.simulator.motion.TripRun;
import dev.pti.simulator.motion.VehicleRun;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;

/**
 * {@code bunching} (DOC-25 §7.2, FR-05): pairs of consecutive buses on a route and direction close up to
 * {@code targetGapRatio} of their scheduled headway, then run together. The follower speeds up (at most half the
 * scheduled travel time of a segment) and the leader dwells longer (at most 60 s a stop) while the gap is larger
 * than the target; once there, the follower is held at the target. A pair ends when either trip ends; no new pair
 * is picked.
 */
public final class BunchingScenario implements Scenario<BunchingScenario.Params> {

    public static final String NAME = "bunching";

    /** Vehicles within this many stops of either end of their trip are not picked (as in DR-30). */
    static final int END_STOPS = 2;

    static final double MAX_DWELL_SECONDS = 60;

    static final double MAX_SPEEDUP_SHARE = 0.5;

    /** Two stops with the same scheduled time still take this long (DOC-25 §5.3). */
    static final double MIN_TRAVEL_SECONDS = 15;

    public record Params(
            @ScenarioParam(label = "Route", type = ParamType.ROUTE) @NotNull
            String routeId,

            @ScenarioParam(
                    label = "Direction",
                    type = ParamType.ENUM,
                    options = {"0", "1"})
            @NotNull
            @Min(0)
            @Max(1)
            Integer directionId,

            @ScenarioParam(label = "Vehicle pairs") @NotNull @Min(1) @Max(3)
            Integer pairs,

            @ScenarioParam(label = "Target gap (share of headway)") @NotNull @DecimalMin("0.05") @DecimalMax("0.45")
            Double targetGapRatio,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 1)
            Duration duration) {}

    private final Feed feed;
    private final Emitter emitter;

    public BunchingScenario(Feed feed, Emitter emitter) {
        this.feed = feed;
        this.emitter = emitter;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Bus bunching";
    }

    @Override
    public String description() {
        return "Make consecutive buses on a route run close together.";
    }

    @Override
    public Concurrency concurrency() {
        return Concurrency.PER_TARGET;
    }

    @Override
    public Class<Params> paramsType() {
        return Params.class;
    }

    @Override
    public Params defaults() {
        return new Params(null, 0, 1, 0.2, Duration.ofMinutes(20));
    }

    @Override
    public List<ScenarioException.FieldError> check(Params params) {
        Optional<Route> route = feed.route(params.routeId());
        if (route.isEmpty()) {
            return List.of(new ScenarioException.FieldError("routeId", "unknown route"));
        }
        return route.get().isRail()
                ? List.of(new ScenarioException.FieldError("routeId", "must be a bus route"))
                : List.of();
    }

    @Override
    public @Nullable String target(Params params) {
        return params.routeId();
    }

    @Override
    public Duration duration(Params params) {
        return params.duration();
    }

    @Override
    public ScenarioHandle start(ScenarioContext context, Params params) {
        List<Pair> pairs = emitter.inspect((vehicles, t) -> pick(vehicles, t, params));
        if (pairs.isEmpty()) {
            throw new ScenarioException(
                    ScenarioException.Problem.NO_ELIGIBLE_VEHICLES,
                    "No two consecutive vehicles on route %s direction %d can be paired now."
                            .formatted(params.routeId(), params.directionId()));
        }
        Overlay overlay = new Overlay(pairs);
        ScenarioHooks.Registration segments = context.hooks().addOverlay(context.runId(), overlay);
        ScenarioHooks.Registration marks = context.hooks().addMarks(context.runId(), overlay);
        return new ScenarioHandle() {
            @Override
            public void stop() {
                segments.remove();
                marks.remove();
            }

            @Override
            public Map<String, Object> progress() {
                return Map.of("pairs", pairs.stream().map(Pair::progress).toList());
            }
        };
    }

    /**
     * Picks up to {@code pairs} disjoint pairs of consecutive vehicles whose follower has the most stops ahead
     * (DOC-25 §7.2 step 1). Vehicles are ordered at the first stop ahead of the follower that both trips serve:
     * the leader is the vehicle scheduled there most recently before the follower. Route 18 and many others have
     * branches and short turns, so distance along each trip's own shape does not order vehicles across patterns,
     * while a shared stop does. Runs inside {@link Emitter#inspect}.
     */
    static List<Pair> pick(List<VehicleRun> vehicles, long t, Params params) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (VehicleRun vehicle : vehicles) {
            TripRun run = vehicle.run();
            TripSchedule s = run.schedule();
            if (!s.routeId().equals(params.routeId())
                    || s.directionId() != params.directionId()
                    || !vehicle.emitting(t)
                    || !run.departed(t)
                    || run.finished(t)
                    || !seen.add(vehicle.vehicleId())) {
                continue;
            }
            TripRun.Motion motion = run.motion(t);
            if (motion.stopIndex() < END_STOPS || motion.stopIndex() > s.lastIndex() - END_STOPS) {
                continue;
            }
            candidates.add(new Candidate(vehicle.vehicleId(), run, motion.stopIndex()));
        }

        List<Pair> possible = new ArrayList<>();
        for (Candidate follower : candidates) {
            Candidate leader = null;
            long headway = Long.MAX_VALUE;
            for (Candidate other : candidates) {
                if (other == follower) {
                    continue;
                }
                long h = headway(follower, other);
                if (h > 0 && h < headway) {
                    leader = other;
                    headway = h;
                }
            }
            if (leader != null) {
                possible.add(new Pair(leader, follower, headway / 1000.0, params.targetGapRatio() * headway / 1000.0));
            }
        }
        possible.sort(
                Comparator.comparingInt((Pair p) -> p.follower.stopsAhead()).reversed());

        List<Pair> chosen = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Pair pair : possible) {
            if (chosen.size() == params.pairs()) {
                break;
            }
            if (!used.contains(pair.leader.vehicleId()) && !used.contains(pair.follower.vehicleId())) {
                used.add(pair.leader.vehicleId());
                used.add(pair.follower.vehicleId());
                chosen.add(pair);
            }
        }
        return chosen;
    }

    /**
     * How long after {@code other} the follower is scheduled at the first stop ahead of it that both serve, in
     * milliseconds; 0 or less when {@code other} is not ahead, or when they share no stop ahead.
     */
    private static long headway(Candidate follower, Candidate other) {
        TripSchedule fs = follower.run().schedule();
        TripSchedule os = other.run().schedule();
        for (int j = follower.stopIndex(); j <= fs.lastIndex(); j++) {
            int i = os.indexOf(fs.stop(j).id());
            if (i >= 0) {
                return follower.run().scheduledMillis(fs.arrival(j))
                        - other.run().scheduledMillis(os.arrival(i));
            }
        }
        return 0;
    }

    record Candidate(String vehicleId, TripRun run, int stopIndex) {

        int stopsAhead() {
            return run.schedule().lastIndex() - stopIndex;
        }
    }

    /** A leader and its follower; state is written on the {@code sim-emitter} thread only. */
    static final class Pair {

        final Candidate leader;
        final Candidate follower;
        final double headwaySeconds;
        final double targetSeconds;
        private volatile double gapSeconds = Double.NaN;
        private volatile boolean active = true;

        Pair(Candidate leader, Candidate follower, double headwaySeconds, double targetSeconds) {
            this.leader = leader;
            this.follower = follower;
            this.headwaySeconds = headwaySeconds;
            this.targetSeconds = targetSeconds;
        }

        boolean active() {
            return active;
        }

        double gapSeconds() {
            return gapSeconds;
        }

        /**
         * The gap at the first stop from the follower's stop {@code fromIndex} on that the leader also serves: when
         * the follower is expected there with {@code followerDelayMillis} of delay, minus when the leader passed (or
         * is expected to pass) it. {@code NaN} when they share no stop ahead.
         */
        double gapFrom(int fromIndex, long followerDelayMillis) {
            TripRun f = follower.run();
            TripRun l = leader.run();
            int fi = fromIndex;
            int li = -1;
            for (; fi <= f.schedule().lastIndex(); fi++) {
                li = l.schedule().indexOf(f.schedule().stop(fi).id());
                if (li >= 0) {
                    break;
                }
            }
            if (li < 0) {
                return Double.NaN;
            }
            long followerArrivalMillis = f.scheduledMillis(f.schedule().arrival(fi)) + followerDelayMillis;
            long pass = l.arrivalMillis(li);
            if (pass == Long.MIN_VALUE) {
                int next = l.segment() + 1;
                long delay =
                        l.arrivalMillis(next) - l.scheduledMillis(l.schedule().arrival(next));
                pass = l.scheduledMillis(l.schedule().arrival(li)) + delay;
            }
            double gap = (followerArrivalMillis - pass) / 1000.0;
            gapSeconds = gap;
            return gap;
        }

        Map<String, Object> progress() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("leaderVehicleId", leader.vehicleId());
            p.put("followerVehicleId", follower.vehicleId());
            p.put("headwaySeconds", Math.round(headwaySeconds));
            p.put("targetGapSeconds", Math.round(targetSeconds));
            double gap = gapSeconds;
            if (!Double.isNaN(gap)) {
                p.put("gapSeconds", Math.round(gap));
            }
            p.put("active", active);
            return p;
        }
    }

    /** The follower's speed-up, the leader's longer dwell, and the marks on both. */
    static final class Overlay implements SegmentOverlay, ScenarioHooks.RunMarks {

        private final List<Pair> pairs;

        Overlay(List<Pair> pairs) {
            this.pairs = List.copyOf(pairs);
        }

        @Override
        public double segmentDelta(TripRun run, int fromIndex, long departureMillis, double departureDelay) {
            for (Pair pair : pairs) {
                if (pair.active && run == pair.follower.run()) {
                    TripSchedule s = run.schedule();
                    int next = fromIndex + 1;
                    double gap = pair.gapFrom(next, Math.round(departureDelay * 1000));
                    if (Double.isNaN(gap)) {
                        return 0;
                    }
                    double cap =
                            MAX_SPEEDUP_SHARE * Math.max(s.arrival(next) - s.departure(fromIndex), MIN_TRAVEL_SECONDS);
                    double error = gap - pair.targetSeconds;
                    // Too far behind: catch up. Too close: hold back to the target, so the pair keeps one pace.
                    return error > 0 ? -Math.min(cap, error) : Math.min(cap, -error);
                }
            }
            return 0;
        }

        @Override
        public double dwellDelta(TripRun run, int stopIndex, long arrivalMillis) {
            for (Pair pair : pairs) {
                if (pair.active && run == pair.leader.run()) {
                    TripRun f = pair.follower.run();
                    int next = f.segment() + 1;
                    long arrival = f.arrivalMillis(next);
                    if (arrival == Long.MIN_VALUE) {
                        return 0;
                    }
                    double gap = pair.gapFrom(
                            next, arrival - f.scheduledMillis(f.schedule().arrival(next)));
                    return Double.isNaN(gap) ? 0 : Math.clamp(gap - pair.targetSeconds, 0, MAX_DWELL_SECONDS);
                }
            }
            return 0;
        }

        @Override
        public boolean marks(TripRun run, long t) {
            for (Pair pair : pairs) {
                if (pair.active && (run == pair.leader.run() || run == pair.follower.run())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void sweep(long t) {
            for (Pair pair : pairs) {
                if (pair.active
                        && (pair.leader.run().finished(t) || pair.follower.run().finished(t))) {
                    pair.active = false;
                }
            }
        }
    }
}
