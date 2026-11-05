package dev.pti.etl.write.adapter.out.jdbc;

import dev.pti.etl.write.application.port.RefundOriginals;
import dev.pti.etl.write.domain.OriginalSale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link RefundOriginals} on {@code dw.fact_ticket_sales}, one query for the whole chunk (DOC-16 §2.3). */
public final class JdbcRefundOriginals implements RefundOriginals {

    private static final String FIND_ORIGINALS = """
            SELECT DISTINCT ON (transaction_id) transaction_id, amount, txn_type
            FROM dw.fact_ticket_sales
            WHERE transaction_id = ANY(?)
            ORDER BY transaction_id, source_lsn DESC
            """;

    private final JdbcTemplate jdbc;

    public JdbcRefundOriginals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, OriginalSale> find(Set<UUID> transactionIds) {
        Map<UUID, OriginalSale> found = new HashMap<>();
        jdbc.query(
                FIND_ORIGINALS,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", transactionIds.toArray())),
                rs -> {
                    found.put(
                            rs.getObject("transaction_id", UUID.class),
                            new OriginalSale(rs.getBigDecimal("amount"), rs.getString("txn_type")));
                });
        return found;
    }
}
