package dev.pti.simulator.emit;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.motion.Fleet;
import dev.pti.simulator.motion.Seeds;
import dev.pti.simulator.motion.VehicleRun;
import dev.pti.simulator.rate.RateControl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The emission loop (DOC-25 §6.1). Each tick handles every emission due in {@code (previous tick, businessNow]}:
 * VehiclePositions at {@code phase(vehicle_id) + k × interval}, TripUpdates at {@code phase(trip_id) + k × interval}
 * and on each arrival at a stop. The event timestamp is the due time, not the time the loop got there, so business
 * keys are discrete and deterministic. Not thread-safe; runs on the {@code sim-emitter} thread only.
 */
public final class Emitter {

    private static final Logger log = LoggerFactory.getLogger(Emitter.class);

    /** A periodic TripUpdate this soon after one sent on arrival is skipped (DOC-25 §6.1). */
    static final long ARRIVAL_UPDATE_QUIET_MILLIS = 5_000;

    /** A tick never catches up more than this; older emissions are dropped with a warning. */
    static final long MAX_BACKLOG_MILLIS = 30_000;

    private static final long WARN_EVERY_MILLIS = 10_000;

    private final BusinessClock clock;
    private final Fleet fleet;
    private final MessageFactory messages;
    private final MessageSink sink;
    private final RateControl rate;
    private final long vehiclePositionMillis;
    private final long tripUpdateMillis;
    /** Written by the emitter thread only; volatile so that the tick-lag gauge reads a whole value. */
    private volatile long previous = Long.MIN_VALUE;

    private volatile int activeVehicles;
    private volatile int activeTrips;
    private volatile double tickLagSeconds;
    private long lastBacklogWarning = Long.MIN_VALUE;

    public Emitter(
            BusinessClock clock,
            Fleet fleet,
            MessageFactory messages,
            MessageSink sink,
            RateControl rate,
            Duration vehiclePositionInterval,
            Duration tripUpdateInterval) {
        this.clock = clock;
        this.fleet = fleet;
        this.messages = messages;
        this.sink = sink;
        this.rate = rate;
        this.vehiclePositionMillis = vehiclePositionInterval.toMillis();
        this.tripUpdateMillis = tripUpdateInterval.toMillis();
    }

    /** One pass of the loop, up to the business time now. */
    public void tick() {
        tickUntil(clock.millis());
        tickLagSeconds = Math.max(0, clock.millis() - previous) / 1000.0;
    }

    /** Handles every emission due in {@code (previous tick, now]}. Visible for tests driving a manual clock. */
    public void tickUntil(long now) {
        long from = previous == Long.MIN_VALUE ? now : previous;
        if (now - from > MAX_BACKLOG_MILLIS) {
            if (lastBacklogWarning == Long.MIN_VALUE || now - lastBacklogWarning >= WARN_EVERY_MILLIS) {
                log.warn(
                        "Emitter fell {} ms behind; dropping emissions older than {} ms",
                        now - from,
                        MAX_BACKLOG_MILLIS);
                lastBacklogWarning = now;
            }
            from = now - MAX_BACKLOG_MILLIS;
        }
        if (now < from) {
            return;
        }
        fleet.refresh(from);
        double multiplier = rate.gtfsRt();
        Set<String> busy = new HashSet<>();
        int vehicles = 0;
        int trips = 0;
        for (VehicleRun run : fleet.runs()) {
            // A late block may still run when the vehicle's next block starts; the earlier one keeps the vehicle.
            boolean owner = busy.add(run.vehicleId());
            if (owner && multiplier > 0) {
                emit(run, from, now, multiplier);
            } else {
                run.advanceTo(now);
                run.markReported(now);
            }
            if (owner && run.emitting(now)) {
                vehicles++;
                if (run.run().departed(now) && !run.run().finished(now)) {
                    trips++;
                }
            }
        }
        activeVehicles = vehicles;
        activeTrips = trips;
        previous = now;
    }

    private void emit(VehicleRun vehicle, long from, long to, double multiplier) {
        long vpInterval = Math.max(1, Math.round(vehiclePositionMillis / multiplier));
        long tuInterval = Math.max(1, Math.round(tripUpdateMillis / multiplier));
        long vpPhase = phase(vehicle.vehicleId(), vpInterval);
        long cursor = from;
        vehicle.advanceTo(cursor);
        while (true) {
            long change = vehicle.nextChangeAfter(cursor);
            long vp = nextSlot(cursor, vpPhase, vpInterval);
            long tu = nextSlot(cursor, phase(vehicle.run().schedule().tripId(), tuInterval), tuInterval);
            long t = Math.min(change, Math.min(vp, tu));
            if (t > to) {
                break;
            }
            vehicle.advanceTo(t);
            if (vehicle.emitting(t)) {
                if (t == change && vehicle.run().arrivesAt(t)) {
                    messages.tripUpdate(vehicle, t).ifPresent(m -> {
                        sink.send(m);
                        vehicle.arrivalUpdateSent(t);
                    });
                }
                if (t == vp) {
                    sink.send(messages.vehiclePosition(vehicle, t));
                }
                if (t == tu && !vehicle.arrivalUpdateWithin(t, ARRIVAL_UPDATE_QUIET_MILLIS)) {
                    messages.tripUpdate(vehicle, t).ifPresent(sink::send);
                }
            }
            cursor = t;
        }
        vehicle.advanceTo(to);
    }

    /** {@code hash(id) mod interval}: spreads vehicles and trips evenly over an interval. */
    static long phase(String id, long interval) {
        return Math.floorMod(Seeds.of(0, id), interval);
    }

    /** The first {@code phase + k × interval} strictly after {@code t}. */
    static long nextSlot(long t, long phase, long interval) {
        return phase + (Math.floorDiv(t - phase, interval) + 1) * interval;
    }

    public int activeVehicles() {
        return activeVehicles;
    }

    public int activeTrips() {
        return activeTrips;
    }

    public void bindTo(MeterRegistry registry) {
        Gauge.builder("pti.sim.active.vehicles", this, Emitter::activeVehicles).register(registry);
        Gauge.builder("pti.sim.active.trips", this, Emitter::activeTrips).register(registry);
        Gauge.builder("pti.sim.synthetic.vehicles", fleet, Fleet::syntheticVehicles)
                .register(registry);
        Gauge.builder("pti.sim.tick.lag", this, e -> e.tickLagSeconds)
                .baseUnit("seconds")
                .register(registry);
    }
}
