package dev.pti.etl.rules;

import dev.pti.common.dq.DlqStage;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.TicketSaleRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** The per-record rules of ticket transactions, DQ-10 and DQ-11 (DOC-16 §2). */
public final class TicketRules {

    private TicketRules() {}

    public static List<RecordRule<TicketSaleRow>> all(DqProperties properties) {
        return List.of(new AmountValid(properties.maxTicketAmount()), new TypeConsistent());
    }

    /** DQ-10: amount between 0 and {@code pti.dq.max-ticket-amount}, in USD. */
    static final class AmountValid implements RecordRule<TicketSaleRow> {

        private final BigDecimal max;

        AmountValid(BigDecimal max) {
            this.max = max;
        }

        @Override
        public String id() {
            return "DQ-10";
        }

        @Override
        public DlqStage stage() {
            return DlqStage.QUALITY;
        }

        @Override
        public boolean appliesDuringReplay() {
            return true;
        }

        @Override
        public Optional<String> check(TicketSaleRow t, RuleContext context) {
            if (t.amount().signum() < 0) {
                return Optional.of("Amount " + t.amount().toPlainString() + " is negative");
            }
            if (t.amount().compareTo(max) > 0) {
                return Optional.of("Amount " + t.amount().toPlainString() + " exceeds " + max.toPlainString());
            }
            if (!"USD".equals(t.currency())) {
                return Optional.of("Currency " + t.currency() + " is not USD");
            }
            return Optional.empty();
        }
    }

    /** DQ-11: a refund names the sale it refunds and a sale does not. */
    static final class TypeConsistent implements RecordRule<TicketSaleRow> {

        @Override
        public String id() {
            return "DQ-11";
        }

        @Override
        public DlqStage stage() {
            return DlqStage.QUALITY;
        }

        @Override
        public boolean appliesDuringReplay() {
            return true;
        }

        @Override
        public Optional<String> check(TicketSaleRow t, RuleContext context) {
            if (t.isRefund() && t.refundOf() == null) {
                return Optional.of("REFUND without refund_of");
            }
            if (!t.isRefund() && t.refundOf() != null) {
                return Optional.of("SALE with refund_of " + t.refundOf());
            }
            return Optional.empty();
        }
    }
}
