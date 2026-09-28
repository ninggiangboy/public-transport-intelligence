package dev.pti.simulator.motion;

import dev.pti.common.message.OccupancyStatus;

/**
 * Samples {@code occupancy_status} for schema v2 (DOC-25 §5.6), seeded by {@code (vehicle_id, trip_id, stopSeq)} so
 * that it stays the same over a segment.
 */
public final class Occupancy {

    private static final OccupancyStatus[] LEVELS = {
        OccupancyStatus.MANY_SEATS_AVAILABLE,
        OccupancyStatus.FEW_SEATS_AVAILABLE,
        OccupancyStatus.STANDING_ROOM_ONLY,
        OccupancyStatus.CRUSHED_STANDING_ROOM_ONLY
    };

    private static final double[] PEAK = {0.30, 0.40, 0.25, 0.05};
    private static final double[] OFF_PEAK = {0.70, 0.25, 0.05, 0.0};

    private Occupancy() {}

    /** The occupancy of {@code vehicleId} on the segment the motion is on. */
    public static OccupancyStatus of(long seed, String vehicleId, TripRun run, TripRun.Motion motion) {
        int index = motion.segment();
        String tripId = run.schedule().tripId();
        Period period = run.period(run.schedule().departure(index));
        double u = Seeds.unit(
                Seeds.of(seed, "occupancy", vehicleId, tripId, run.schedule().stopSequence(index)));
        double[] weights = period == Period.PEAK ? PEAK : OFF_PEAK;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (u < cumulative) {
                return LEVELS[i];
            }
        }
        return LEVELS[0];
    }
}
