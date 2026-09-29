package dev.pti.simulator.emit;

import dev.pti.simulator.motion.TripRun;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which scenario run affects a vehicle's trip (DOC-25 §7.1): its messages carry that run's id in the ledger. Called
 * on the {@code sim-emitter} thread.
 */
public interface VehicleMarks {

    VehicleMarks NONE = new VehicleMarks() {
        @Override
        public @Nullable UUID runIdFor(TripRun run, long t) {
            return null;
        }

        @Override
        public void sweep(long t) {}
    };

    /** The run affecting {@code run} at business time {@code t}, or {@code null}. */
    @Nullable
    UUID runIdFor(TripRun run, long t);

    /** Called at the end of every emitter tick, with every vehicle advanced to {@code t}. */
    void sweep(long t);
}
