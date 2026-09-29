package dev.pti.etl.write;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.RuleViolationException;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.WriteSet;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DQ-12 and DQ-13 (DOC-16 §2.3): a refund must refer to a real sale and must not exceed it. The original is looked
 * up in the chunk first, then with one query for the whole chunk, inside the chunk transaction.
 */
public final class RefundRule {

    public static final String ORPHAN = "DQ-12";
    public static final String EXCEEDS = "DQ-13";

    private static final String FIND_ORIGINALS = """
            SELECT DISTINCT ON (transaction_id) transaction_id, amount, txn_type
            FROM dw.fact_ticket_sales
            WHERE transaction_id = ANY(?)
            ORDER BY transaction_id, source_lsn DESC
            """;

    private final JdbcTemplate jdbc;
    private final DqProperties properties;

    public RefundRule(JdbcTemplate jdbc, DqProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public ChunkRuleResult apply(List<WriteSet> items, WriteContext context) {
        boolean orphanOn = properties.enabled(ORPHAN) && !context.replay();
        boolean exceedsOn = properties.enabled(EXCEEDS);
        List<TicketSaleRow> refunds = items.stream()
                .flatMap(i -> i.ticketSales().stream())
                .filter(r -> r.isRefund() && r.refundOf() != null)
                .toList();
        if (refunds.isEmpty() || (!orphanOn && !exceedsOn)) {
            return ChunkRuleResult.keepAll(items);
        }

        Map<UUID, Original> originals = new HashMap<>();
        for (WriteSet item : items) {
            for (TicketSaleRow row : item.ticketSales()) {
                originals.put(row.transactionId(), new Original(row.amount(), row.txnType()));
            }
        }
        Set<UUID> missing = new LinkedHashSet<>();
        for (TicketSaleRow refund : refunds) {
            if (!originals.containsKey(refund.refundOf())) {
                missing.add(refund.refundOf());
            }
        }
        if (!missing.isEmpty()) {
            originals.putAll(findOriginals(missing));
        }

        List<WriteSet> kept = new ArrayList<>();
        List<ChunkRuleResult.Rejected> rejected = new ArrayList<>();
        for (WriteSet item : items) {
            @Nullable
            RuleViolationException violation = item.ticketSales().isEmpty()
                    ? null
                    : check(item.ticketSales().getFirst(), originals, context, orphanOn, exceedsOn);
            if (violation == null) {
                kept.add(item);
            } else {
                rejected.add(new ChunkRuleResult.Rejected(item, violation));
            }
        }
        return new ChunkRuleResult(kept, rejected, 0);
    }

    private @Nullable RuleViolationException check(
            TicketSaleRow row,
            Map<UUID, Original> originals,
            WriteContext context,
            boolean orphanOn,
            boolean exceedsOn) {
        if (!row.isRefund() || row.refundOf() == null) {
            return null;
        }
        Original original = originals.get(row.refundOf());
        if (original == null) {
            Duration age = Duration.between(row.createdAt(), context.businessNow());
            boolean live = "c".equals(row.op()) || "u".equals(row.op());
            if (orphanOn && live && age.compareTo(properties.refundGrace()) > 0) {
                return new RuleViolationException(
                        DlqStage.BUSINESS,
                        ORPHAN,
                        "Refund " + row.transactionId() + " refers to transaction " + row.refundOf()
                                + ", which was not found " + age.toMinutes() + " minutes after the refund");
            }
            return null;
        }
        if (!exceedsOn) {
            return null;
        }
        if ("REFUND".equals(original.txnType())) {
            return new RuleViolationException(
                    DlqStage.BUSINESS,
                    EXCEEDS,
                    "Refund " + row.transactionId() + " refers to " + row.refundOf() + ", which is itself a refund");
        }
        if (row.amount().compareTo(original.amount()) > 0) {
            return new RuleViolationException(
                    DlqStage.BUSINESS,
                    EXCEEDS,
                    "Refund amount " + row.amount().toPlainString() + " exceeds the original amount "
                            + original.amount().toPlainString() + " of " + row.refundOf());
        }
        return null;
    }

    private Map<UUID, Original> findOriginals(Set<UUID> ids) {
        Map<UUID, Original> found = new HashMap<>();
        jdbc.query(
                FIND_ORIGINALS, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())), rs -> {
                    found.put(
                            rs.getObject("transaction_id", UUID.class),
                            new Original(rs.getBigDecimal("amount"), rs.getString("txn_type")));
                });
        return found;
    }

    private record Original(BigDecimal amount, String txnType) {}
}
