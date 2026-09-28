package dev.pti.simulator.rate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two rate multipliers (DOC-25 §6.5, DR-68): {@code gtfsRt} divides the emission intervals, {@code ticketing}
 * multiplies the sales rate. {@code 0} pauses a stream. Changed at runtime by {@code PUT /sim/rate}, not persisted.
 */
public final class RateControl {

    private static final Logger log = LoggerFactory.getLogger(RateControl.class);

    public static final double MIN = 0.1;
    public static final double MAX = 20;

    private volatile double gtfsRt;
    private volatile double ticketing;

    public RateControl(double gtfsRt, double ticketing) {
        this.gtfsRt = check("gtfsRt", gtfsRt);
        this.ticketing = check("ticketing", ticketing);
    }

    /** Whether {@code value} is 0 or within [0.1, 20]. */
    public static boolean allowed(double value) {
        return value == 0 || (value >= MIN && value <= MAX);
    }

    public double gtfsRt() {
        return gtfsRt;
    }

    public double ticketing() {
        return ticketing;
    }

    /** Changes one or both multipliers; a {@code null} leaves that one unchanged. */
    public synchronized void set(@Nullable Double newGtfsRt, @Nullable Double newTicketing) {
        if (newGtfsRt != null) {
            check("gtfsRt", newGtfsRt);
        }
        if (newTicketing != null) {
            check("ticketing", newTicketing);
        }
        if (newGtfsRt != null) {
            gtfsRt = newGtfsRt;
        }
        if (newTicketing != null) {
            ticketing = newTicketing;
        }
        log.info("Rate multiplier changed: gtfsRt={} ticketing={}", gtfsRt, ticketing);
    }

    public void bindTo(MeterRegistry registry) {
        Gauge.builder("pti.sim.rate.multiplier", this, RateControl::gtfsRt)
                .tag("stream", "gtfs-rt")
                .register(registry);
        Gauge.builder("pti.sim.rate.multiplier", this, RateControl::ticketing)
                .tag("stream", "ticketing")
                .register(registry);
    }

    private static double check(String name, double value) {
        if (!allowed(value)) {
            throw new IllegalArgumentException(
                    name + " must be 0 or between " + MIN + " and " + MAX + ", was " + value);
        }
        return value;
    }
}
