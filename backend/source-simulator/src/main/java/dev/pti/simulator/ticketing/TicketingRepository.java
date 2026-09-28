package dev.pti.simulator.ticketing;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes to {@code ticketing_source} as {@code source_simulator} (DOC-13 §5, DOC-17). Every call autocommits. */
public final class TicketingRepository {

    private final JdbcTemplate jdbc;

    public TicketingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the sale points that do not exist yet; returns how many were inserted. */
    public int upsertSalePoints(List<SalePoint> points) {
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO public.sale_point (sale_point_id, name, kind, stop_id, route_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (sale_point_id) DO NOTHING
                """, points, 100, (ps, p) -> {
            ps.setString(1, p.id());
            ps.setString(2, p.name());
            ps.setString(3, p.kind().name());
            ps.setString(4, p.stopId());
            ps.setString(5, p.routeId());
        });
        int inserted = 0;
        for (int[] batch : counts) {
            for (int n : batch) {
                inserted += Math.max(n, 0);
            }
        }
        return inserted;
    }

    /** Inserts a sale or a refund; {@code updated_at} starts equal to {@code created_at} (DOC-25 §9.3). */
    public void insert(Transaction t) {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(t.createdAt(), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO public.ticket_transaction
                  (transaction_id, sale_point_id, route_id, stop_id, ticket_type, txn_type, amount,
                   refund_of, customer_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setObject(1, t.id());
            ps.setString(2, t.salePointId());
            ps.setString(3, t.routeId());
            ps.setString(4, t.stopId());
            ps.setString(5, t.ticketType().name());
            ps.setString(6, t.isRefund() ? "REFUND" : "SALE");
            ps.setBigDecimal(7, t.amount());
            if (t.refundOf() == null) {
                ps.setNull(8, Types.OTHER);
            } else {
                ps.setObject(8, t.refundOf());
            }
            ps.setString(9, t.customerRef());
            ps.setObject(10, createdAt);
            ps.setObject(11, createdAt);
        });
    }

    /** Marks a sale voided; the trigger sets {@code updated_at} to the database's real time. */
    public void voidTransaction(UUID id) {
        jdbc.update(
                "UPDATE public.ticket_transaction SET status = 'VOIDED' WHERE transaction_id = ? AND status <> 'VOIDED'",
                id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM public.ticket_transaction WHERE transaction_id = ?", id);
    }
}
