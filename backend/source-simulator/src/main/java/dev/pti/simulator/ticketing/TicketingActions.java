package dev.pti.simulator.ticketing;

/** What a {@link TicketingOverlay} may do (DOC-25 §7.6, §7.7). */
public interface TicketingActions {

    /** Whether ticketing is paused ({@code rateMultiplier.ticketing = 0}, DOC-25 §6.5). */
    boolean paused();

    SaleGenerator generator();

    SalePointCatalog catalog();

    /** Inserts a sale or a refund, like the regular seeder does. */
    void insert(Transaction transaction);
}
