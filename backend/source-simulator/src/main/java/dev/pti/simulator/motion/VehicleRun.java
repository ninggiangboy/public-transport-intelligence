package dev.pti.simulator.motion;

import dev.pti.simulator.feed.Block;
import dev.pti.simulator.feed.TripSchedule;
import java.time.LocalDate;

/**
 * A vehicle working its block on one service date (DOC-25 §4.4): its trips in order, each starting no earlier than
 * the previous one ended. Not thread-safe; owned by the emitter thread.
 */
public final class VehicleRun {

    private final Block block;
    private final String vehicleId;
    private final LocalDate serviceDate;
    private final long baseMillis;
    private final DelayModel model;
    private final long maxLayoverEmitMillis;
    private int tripIndex;
    private TripRun run;
    private long previousEndMillis = Long.MIN_VALUE;
    private long lastArrivalUpdateMillis = Long.MIN_VALUE;

    /**
     * Rebuilds the vehicle deterministically from the start of its block up to {@code t} (DOC-25 §10): the same
     * seed and time always put it at the same place. Stops passed before {@code t} count as reported.
     */
    public VehicleRun(
            Block block,
            String vehicleId,
            LocalDate serviceDate,
            long baseMillis,
            DelayModel model,
            long maxLayoverEmitMillis,
            long t) {
        this.block = block;
        this.vehicleId = vehicleId;
        this.serviceDate = serviceDate;
        this.baseMillis = baseMillis;
        this.model = model;
        this.maxLayoverEmitMillis = maxLayoverEmitMillis;
        this.run = TripRun.start(block.trips().getFirst(), serviceDate, baseMillis, model, Long.MIN_VALUE);
        advanceTo(t);
        run.markReported(t);
    }

    public String vehicleId() {
        return vehicleId;
    }

    public Block block() {
        return block;
    }

    public LocalDate serviceDate() {
        return serviceDate;
    }

    /** The current trip, or the next one while the vehicle waits at its first stop. */
    public TripRun run() {
        return run;
    }

    /**
     * Moves to {@code t}. A finished trip stays current until strictly after its last arrival, so the arrival there
     * can still be reported; then the next trip of the block starts.
     */
    public void advanceTo(long t) {
        run.advanceTo(t);
        while (run.finished(t)
                && t > run.endMillis()
                && tripIndex + 1 < block.trips().size()) {
            previousEndMillis = run.endMillis();
            tripIndex++;
            TripSchedule next = block.trips().get(tripIndex);
            run = TripRun.start(next, serviceDate, baseMillis, model, previousEndMillis);
            run.advanceTo(t);
        }
    }

    /** The scheduled start of the block, in epoch milliseconds. */
    public long startMillis() {
        return baseMillis + block.start() * 1000L;
    }

    /**
     * The next moment after {@code t} at which the vehicle changes state: an arrival, a departure that makes the next
     * arrival known, or the switch to the next trip of the block. Call {@link #advanceTo} with {@code t} first.
     */
    public long nextChangeAfter(long t) {
        long next = run.nextChangeAfter(t);
        if (next == Long.MAX_VALUE
                && run.finished(t)
                && tripIndex + 1 < block.trips().size()) {
            return Math.max(run.endMillis(), t) + 1;
        }
        return next;
    }

    /** Marks the stops passed by {@code t} as reported, while nothing is sent (DOC-25 §6.5). */
    public void markReported(long t) {
        run.markReported(t);
    }

    /** Whether a TripUpdate triggered by an arrival was sent in the {@code millis} before {@code t}. */
    public boolean arrivalUpdateWithin(long t, long millis) {
        return lastArrivalUpdateMillis != Long.MIN_VALUE && t - lastArrivalUpdateMillis <= millis;
    }

    public void arrivalUpdateSent(long t) {
        lastArrivalUpdateMillis = t;
    }

    /** Whether the whole block is over at {@code t}. */
    public boolean done(long t) {
        return tripIndex + 1 == block.trips().size() && run.finished(t) && t > run.endMillis();
    }

    /**
     * Whether the vehicle reports positions at {@code t}: a layover longer than {@code max-layover-emit} counts as
     * a return to the garage until that long before the next departure (DOC-25 §4.4).
     */
    public boolean emitting(long t) {
        if (t < startMillis() || done(t)) {
            return false;
        }
        if (run.departed(t) || previousEndMillis == Long.MIN_VALUE) {
            return true;
        }
        long scheduledDeparture = baseMillis + run.schedule().departure(0) * 1000L;
        boolean longLayover = scheduledDeparture - previousEndMillis > maxLayoverEmitMillis;
        return !longLayover || t >= scheduledDeparture - maxLayoverEmitMillis;
    }
}
