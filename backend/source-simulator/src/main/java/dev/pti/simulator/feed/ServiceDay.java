package dev.pti.simulator.feed;

import java.time.LocalDate;
import java.util.List;

/**
 * One real service date and what runs on it: the blocks of its feed date with their vehicles (DOC-25 §3.3).
 *
 * @param serviceDate the real date; messages carry it as {@code start_date}
 * @param feedDate the date of the feed whose schedule runs
 */
public record ServiceDay(LocalDate serviceDate, LocalDate feedDate, List<AssignedBlock> blocks, int syntheticBuses) {

    public ServiceDay {
        blocks = List.copyOf(blocks);
    }

    /** Trips between their first departure and last arrival at {@code seconds} of the service date. */
    public long tripsRunningAt(int seconds) {
        return blocks.stream()
                .flatMap(b -> b.block().trips().stream())
                .filter(t -> t.firstDeparture() <= seconds && seconds <= t.lastArrival())
                .count();
    }

    /** A block and the vehicle that runs it. */
    public record AssignedBlock(Block block, String vehicleId) {}
}
