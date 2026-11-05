package dev.pti.etl.write.application;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.RuleViolationException;
import dev.pti.etl.core.domain.TicketSaleRow;
import dev.pti.etl.core.domain.WriteSet;
import dev.pti.etl.write.application.port.RefundOriginals;
import dev.pti.etl.write.domain.ChunkRuleResult;
import dev.pti.etl.write.domain.OriginalSale;
import dev.pti.etl.write.domain.WriteContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * DQ-12 and DQ-13 (DOC-16 §2.3): a refund must refer to a real sale and must not exceed it. The original is looked
 * up in the chunk first, then with one query for the whole chunk, inside the chunk transaction.
 */
public final class RefundRule {

    public static final String ORPHAN = "DQ-12";
    public static final String EXCEEDS = "DQ-13";

    private final RefundOriginals originals;
    private final Predicate<String> enabled;
    private final Duration refundGrace;

    public RefundRule(RefundOriginals originals, Predicate<String> enabled, Duration refundGrace) {
        this.originals = originals;
        this.enabled = enabled;
        this.refundGrace = refundGrace;
    }

    public ChunkRuleResult apply(List<WriteSet> items, WriteContext context) {
        boolean orphanOn = enabled.test(ORPHAN) && !context.replay();
        boolean exceedsOn = enabled.test(EXCEEDS);
        List<TicketSaleRow> refunds = items.stream()
                .flatMap(i -> i.ticketSales().stream())
                .filter(r -> r.isRefund() && r.refundOf() != null)
                .toList();
        if (refunds.isEmpty() || (!orphanOn && !exceedsOn)) {
            return ChunkRuleResult.keepAll(items);
        }

        Map<UUID, OriginalSale> found = new HashMap<>();
        for (WriteSet item : items) {
            for (TicketSaleRow row : item.ticketSales()) {
                found.put(row.transactionId(), new OriginalSale(row.amount(), row.txnType()));
            }
        }
        Set<UUID> missing = new LinkedHashSet<>();
        for (TicketSaleRow refund : refunds) {
            if (!found.containsKey(refund.refundOf())) {
                missing.add(refund.refundOf());
            }
        }
        if (!missing.isEmpty()) {
            found.putAll(originals.find(missing));
        }

        List<WriteSet> kept = new ArrayList<>();
        List<ChunkRuleResult.Rejected> rejected = new ArrayList<>();
        for (WriteSet item : items) {
            @Nullable
            RuleViolationException violation = item.ticketSales().isEmpty()
                    ? null
                    : check(item.ticketSales().getFirst(), found, context, orphanOn, exceedsOn);
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
            Map<UUID, OriginalSale> originals,
            WriteContext context,
            boolean orphanOn,
            boolean exceedsOn) {
        if (!row.isRefund() || row.refundOf() == null) {
            return null;
        }
        OriginalSale original = originals.get(row.refundOf());
        if (original == null) {
            Duration age = Duration.between(row.createdAt(), context.businessNow());
            boolean live = "c".equals(row.op()) || "u".equals(row.op());
            if (orphanOn && live && age.compareTo(refundGrace) > 0) {
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
}
