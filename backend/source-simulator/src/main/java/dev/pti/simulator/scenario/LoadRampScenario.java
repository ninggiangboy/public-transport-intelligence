package dev.pti.simulator.scenario;

import dev.pti.simulator.rate.RateControl;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * {@code load-ramp} (DOC-25 §7.8, EXP-05, EXP-07): sets {@code rateMultiplier.gtfsRt} (and, if asked, the ticketing
 * one) step by step. With {@code rampDown} the steps are walked back after the last one. When the run ends or is
 * stopped, the multipliers go back to what they were before it started. {@code PUT /sim/rate} is refused meanwhile.
 */
public final class LoadRampScenario implements Scenario<LoadRampScenario.Params> {

    public static final String NAME = "load-ramp";

    public record Params(
            @ScenarioParam(label = "Rate multipliers") @NotNull @NotEmpty
            List<@NotNull @DecimalMin("0.1") @DecimalMax("20") Double> steps,

            @ScenarioParam(label = "Step duration") @NotNull @DurationMin(minutes = 1)
            Duration stepDuration,

            @ScenarioParam(label = "Ramp down") @NotNull Boolean rampDown,

            @ScenarioParam(label = "Include ticketing") @NotNull
            Boolean includeTicketing) {

        public Params {
            steps = steps == null ? null : List.copyOf(steps);
        }

        /** The multipliers in order: the steps, then back down without repeating the top one. */
        public List<Double> sequence() {
            List<Double> sequence = new ArrayList<>(steps);
            if (rampDown) {
                for (int i = steps.size() - 2; i >= 0; i--) {
                    sequence.add(steps.get(i));
                }
            }
            return sequence;
        }
    }

    private final RateControl rate;

    public LoadRampScenario(RateControl rate) {
        this.rate = rate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Load ramp";
    }

    @Override
    public String description() {
        return "Raise the GTFS-realtime emission rate step by step.";
    }

    @Override
    public Concurrency concurrency() {
        return Concurrency.SINGLE;
    }

    @Override
    public Class<Params> paramsType() {
        return Params.class;
    }

    @Override
    public Params defaults() {
        return new Params(List.of(1.0, 2.0, 5.0, 10.0), Duration.ofMinutes(5), true, false);
    }

    /** Derived: {@code stepDuration × number of steps}, walked back too with {@code rampDown} (DOC-25 §7.8). */
    @Override
    public Duration duration(Params params) {
        return params.stepDuration().multipliedBy(params.sequence().size());
    }

    @Override
    public ScenarioHandle start(ScenarioContext context, Params params) {
        Ramp ramp = new Ramp(
                rate,
                params.sequence(),
                params.stepDuration().toMillis(),
                params.includeTicketing(),
                context.realStartMillis());
        ramp.tick(context.realStartMillis());
        return ramp;
    }

    /** Applies the step of the current real time; ticks come from the {@code sim-scenarios} thread. */
    static final class Ramp implements ScenarioHandle {

        private final RateControl rate;
        private final List<Double> sequence;
        private final long stepMillis;
        private final boolean ticketing;
        private final long startMillis;
        private final double previousGtfsRt;
        private final double previousTicketing;
        private int step = -1;
        private boolean stopped;

        Ramp(RateControl rate, List<Double> sequence, long stepMillis, boolean ticketing, long startMillis) {
            this.rate = rate;
            this.sequence = List.copyOf(sequence);
            this.stepMillis = stepMillis;
            this.ticketing = ticketing;
            this.startMillis = startMillis;
            this.previousGtfsRt = rate.gtfsRt();
            this.previousTicketing = rate.ticketing();
        }

        @Override
        public synchronized void tick(long realMillis) {
            if (stopped) {
                return;
            }
            int now = (int) Math.min(sequence.size() - 1, Math.max(0, (realMillis - startMillis) / stepMillis));
            if (now != step) {
                step = now;
                double multiplier = sequence.get(step);
                rate.set(multiplier, ticketing ? multiplier : null);
            }
        }

        @Override
        public synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            rate.set(previousGtfsRt, ticketing ? previousTicketing : null);
        }

        @Override
        public synchronized Map<String, Object> progress() {
            return Map.of(
                    "step",
                    step + 1,
                    "steps",
                    sequence.size(),
                    "multiplier",
                    step < 0 ? Double.valueOf(previousGtfsRt) : sequence.get(step));
        }
    }
}
