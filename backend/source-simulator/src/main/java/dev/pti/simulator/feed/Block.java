package dev.pti.simulator.feed;

import java.util.List;

/**
 * The trips one vehicle runs on a feed date, ordered by departure (DOC-13 §4). {@code start} and {@code end} are
 * the first departure and the last arrival in GTFS seconds.
 */
public record Block(String blockId, List<TripSchedule> trips, int start, int end, boolean rail) {

    public Block {
        trips = List.copyOf(trips);
    }
}
