package dev.pti.simulator.ticketing.application.port;

import dev.pti.simulator.ticketing.domain.SalePoint;
import dev.pti.simulator.ticketing.domain.Transaction;
import java.util.List;
import java.util.UUID;

/** Writes to {@code ticketing_source} as {@code source_simulator} (DOC-13 §5, DOC-17). Every call autocommits. */
public interface TicketingRepository {

    /** Inserts the sale points that do not exist yet; returns how many were inserted. */
    int upsertSalePoints(List<SalePoint> points);

    /** Inserts a sale or a refund; {@code updated_at} starts equal to {@code created_at} (DOC-25 §9.3). */
    void insert(Transaction transaction);

    /** Marks a sale voided; the trigger sets {@code updated_at} to the database's real time. */
    void voidTransaction(UUID id);

    void delete(UUID id);
}
