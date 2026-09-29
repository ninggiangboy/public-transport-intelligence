package dev.pti.simulator.ticketing;

/**
 * Extra ticketing activity while a scenario runs (DOC-25 §7.6, §7.7). Called on the {@code sim-ticketing} thread
 * after the regular sales of each tick.
 */
public interface TicketingOverlay {

    /**
     * Acts on the business-time interval {@code (from, now]} in epoch milliseconds.
     *
     * @return {@code false} once the overlay has nothing left to do and can be removed
     */
    boolean onTick(TicketingActions actions, long from, long now);
}
