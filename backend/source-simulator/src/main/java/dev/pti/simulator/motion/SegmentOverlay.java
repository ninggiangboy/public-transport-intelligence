package dev.pti.simulator.motion;

/**
 * A change a scenario makes to the delay model while it runs (DOC-25 §5.3, §7.2, §7.3). Called on the
 * {@code sim-emitter} thread only, each time a vehicle starts a segment.
 */
public interface SegmentOverlay {

    /** No change. */
    SegmentOverlay NONE = new SegmentOverlay() {};

    /**
     * Seconds added to the delay drawn for segment {@code fromIndex → fromIndex + 1} ({@code eps} of DOC-25 §5.3).
     *
     * @param departureMillis when the vehicle leaves stop {@code fromIndex}
     * @param departureDelay the delay at that departure, in seconds
     */
    default double segmentDelta(TripRun run, int fromIndex, long departureMillis, double departureDelay) {
        return 0;
    }

    /** Seconds of dwell added at stop {@code stopIndex}, which the vehicle reaches at {@code arrivalMillis}. */
    default double dwellDelta(TripRun run, int stopIndex, long arrivalMillis) {
        return 0;
    }

    /** Whether stop {@code stopIndex} is reported as {@code SKIPPED} in TripUpdates. */
    default boolean skipped(TripRun run, int stopIndex) {
        return false;
    }
}
