package dev.pti.analytics.recompute.domain;

import dev.pti.analytics.core.domain.EventTimeGrid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ticketing windows that a replay of {@code TICKETING_SALES} makes worth recomputing (DOC-23 §11.1): the 15-minute
 * windows that touch the {@code created_at} range of the replayed sales and are closed. The range is by
 * {@code created_at}, which is business time, because the {@code event_timestamp} of a ticket is the commit time at
 * the source, in real time (DR-67). Only the choice of windows is here; the detector that evaluates them is P6-05.
 */
public final class TicketingWindows {

    private TicketingWindows() {}

    /**
     * @param businessNow business time now
     * @param window the window length, {@code pti.analytics.ticketing.window}
     * @param allowedLateness a window is closed once {@code businessNow − allowedLateness} has passed its end
     * @return the start of each window that touches {@code [minCreatedAt, maxCreatedAt]} and is closed, oldest first
     */
    public static List<Instant> closedWindows(
            Instant minCreatedAt,
            Instant maxCreatedAt,
            Instant businessNow,
            Duration window,
            Duration allowedLateness) {
        Instant closedBefore = businessNow.minus(allowedLateness);
        List<Instant> starts = new ArrayList<>();
        for (Instant start = EventTimeGrid.floorGrid(minCreatedAt, window);
                !start.isAfter(maxCreatedAt);
                start = start.plus(window)) {
            if (!start.plus(window).isAfter(closedBefore)) {
                starts.add(start);
            }
        }
        return starts;
    }
}
