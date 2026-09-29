package dev.pti.simulator.scenario;

import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.motion.Seeds;
import dev.pti.simulator.motion.SegmentOverlay;
import dev.pti.simulator.motion.TripRun;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;

/**
 * {@code disruption} (DOC-25 §7.3, FR-07): every trip on the route (and direction) gets {@code extraDelayPerStop}
 * more delay on each segment until its extra delay reaches {@code maxExtraDelay}. After the run, trips still on the
 * road win back 30 s per segment until their extra delay is gone; new trips are not affected. With
 * {@code skipStops}, each affected trip skips 20% of the stops it has left.
 */
public final class DisruptionScenario implements Scenario<DisruptionScenario.Params> {

    public static final String NAME = "disruption";

    /** Recovery per segment once the run is over. */
    static final double RECOVERY_SECONDS = 30;

    static final double SKIP_SHARE = 0.2;

    public record Params(
            @ScenarioParam(label = "Route", type = ParamType.ROUTE) @NotNull
            String routeId,

            @ScenarioParam(
                    label = "Direction",
                    type = ParamType.ENUM,
                    options = {"0", "1"},
                    nullable = true)
            @Min(0)
            @Max(1)
            @Nullable
            Integer directionId,

            @ScenarioParam(label = "Extra delay per stop") @NotNull @DurationMin(seconds = 10) @DurationMax(minutes = 5)
            Duration extraDelayPerStop,

            @ScenarioParam(label = "Maximum extra delay") @NotNull @DurationMin(seconds = 10) @DurationMax(hours = 1)
            Duration maxExtraDelay,

            @ScenarioParam(label = "Skip stops") @NotNull Boolean skipStops,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 1)
            Duration duration) {}

    private final Feed feed;

    public DisruptionScenario(Feed feed) {
        this.feed = feed;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Service disruption";
    }

    @Override
    public String description() {
        return "Add growing delays to every trip on a route.";
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
        return new Params(null, null, Duration.ofSeconds(60), Duration.ofMinutes(15), false, Duration.ofMinutes(20));
    }

    @Override
    public List<ScenarioException.FieldError> check(Params params) {
        return feed.route(params.routeId()).isPresent()
                ? List.of()
                : List.of(new ScenarioException.FieldError("routeId", "unknown route"));
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
        Overlay overlay = new Overlay(context, params);
        overlay.overlay = context.hooks().addOverlay(context.runId(), overlay);
        overlay.marks = context.hooks().addMarks(context.runId(), overlay);
        return new ScenarioHandle() {
            @Override
            public void stop() {
                overlay.active = false;
            }

            @Override
            public Map<String, Object> progress() {
                return Map.of("affectedTrips", overlay.affected, "maxExtraDelaySeconds", overlay.maxExtra);
            }
        };
    }

    /** Runs on the {@code sim-emitter} thread; {@code active} is flipped by the API. */
    private static final class Overlay implements SegmentOverlay, ScenarioHooks.RunMarks {

        private final ScenarioContext context;
        private final String routeId;
        private final @Nullable Integer directionId;
        private final double perStop;
        private final double max;
        private final boolean skipStops;
        /** Extra delay per trip; weak, so finished trips go away with the fleet. */
        private final Map<TripRun, Double> extra = new WeakHashMap<>();

        private final Map<TripRun, BitSet> skipped = new WeakHashMap<>();
        private volatile boolean active = true;
        private volatile int affected;
        private volatile double maxExtra;
        private ScenarioHooks.@Nullable Registration overlay;
        private ScenarioHooks.@Nullable Registration marks;

        Overlay(ScenarioContext context, Params params) {
            this.context = context;
            this.routeId = params.routeId();
            this.directionId = params.directionId();
            this.perStop = params.extraDelayPerStop().toMillis() / 1000.0;
            this.max = params.maxExtraDelay().toMillis() / 1000.0;
            this.skipStops = params.skipStops();
        }

        private boolean onRoute(TripRun run) {
            return run.schedule().routeId().equals(routeId)
                    && (directionId == null || run.schedule().directionId() == directionId);
        }

        @Override
        public double segmentDelta(TripRun run, int fromIndex, long departureMillis, double departureDelay) {
            if (!onRoute(run)) {
                return 0;
            }
            double current = extra.getOrDefault(run, 0.0);
            if (active) {
                double add = Math.min(perStop, max - current);
                if (add <= 0) {
                    return 0;
                }
                extra.put(run, current + add);
                if (skipStops && !skipped.containsKey(run)) {
                    skipped.put(run, pickSkipped(run, fromIndex));
                }
                return add;
            }
            if (current <= 0) {
                return 0;
            }
            double back = Math.min(RECOVERY_SECONDS, current);
            extra.put(run, current - back);
            return -back;
        }

        /** 20% of the stops after {@code fromIndex + 1}, never the last one, chosen by hash. */
        private BitSet pickSkipped(TripRun run, int fromIndex) {
            BitSet stops = new BitSet();
            for (int j = fromIndex + 2; j < run.schedule().lastIndex(); j++) {
                long h = Seeds.of(
                        context.seed(),
                        NAME,
                        run.schedule().tripId(),
                        j,
                        context.runId().toString());
                if (Seeds.unit(h) < SKIP_SHARE) {
                    stops.set(j);
                }
            }
            return stops;
        }

        @Override
        public boolean skipped(TripRun run, int stopIndex) {
            BitSet stops = skipped.get(run);
            return stops != null && stops.get(stopIndex);
        }

        @Override
        public boolean marks(TripRun run, long t) {
            return onRoute(run) && (active || extra.getOrDefault(run, 0.0) > 0);
        }

        @Override
        public void sweep(long t) {
            int count = 0;
            double top = 0;
            for (Map.Entry<TripRun, Double> e : extra.entrySet()) {
                if (e.getValue() > 0 && !e.getKey().finished(t)) {
                    count++;
                    top = Math.max(top, e.getValue());
                }
            }
            affected = count;
            maxExtra = top;
            if (!active && count == 0) {
                // Recovered: nothing left to act on. Skipped stops of finished trips no longer matter.
                if (overlay != null) {
                    overlay.remove();
                }
                if (marks != null) {
                    marks.remove();
                }
            }
        }
    }
}
