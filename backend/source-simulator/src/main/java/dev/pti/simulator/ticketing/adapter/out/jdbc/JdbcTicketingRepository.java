package dev.pti.simulator.ticketing.adapter.out.jdbc;

import dev.pti.common.error.TransientInfraException;
import dev.pti.simulator.ticketing.application.port.TicketingRepository;
import dev.pti.simulator.ticketing.domain.SalePoint;
import dev.pti.simulator.ticketing.domain.Transaction;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link TicketingRepository} on {@code ticketing_source} (DOC-13 §5, DOC-17). Every call autocommits. */
public final class JdbcTicketingRepository implements TicketingRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int upsertSalePoints(List<SalePoint> points) {
        try {
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
        } catch (DataAccessException e) {
            throw new TransientInfraException("Cannot upsert sale points", e);
        }
    }

    @Override
    public void insert(Transaction t) {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(t.createdAt(), ZoneOffset.UTC);
        try {
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
        } catch (DataAccessException e) {
            throw new TransientInfraException("Cannot insert a ticket transaction", e);
        }
    }

    @Override
    public void voidTransaction(UUID id) {
        try {
            jdbc.update(
                    "UPDATE public.ticket_transaction SET status = 'VOIDED' WHERE transaction_id = ?"
                            + " AND status <> 'VOIDED'",
                    id);
        } catch (DataAccessException e) {
            throw new TransientInfraException("Cannot void a ticket transaction", e);
        }
    }

    @Override
    public void delete(UUID id) {
        try {
            jdbc.update("DELETE FROM public.ticket_transaction WHERE transaction_id = ?", id);
        } catch (DataAccessException e) {
            throw new TransientInfraException("Cannot delete a ticket transaction", e);
        }
    }
}
