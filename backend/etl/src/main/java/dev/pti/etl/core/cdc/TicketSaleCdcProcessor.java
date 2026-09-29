package dev.pti.etl.core.cdc;

import dev.pti.common.error.SchemaViolationException;
import dev.pti.common.message.BusinessKey;
import dev.pti.common.message.PayloadHasher;
import dev.pti.etl.core.BestEffortKeys;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.rules.RuleEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Ticket transaction CDC events to {@code fact_ticket_sales} rows (DOC-20 §4.4, DR-07). */
public final class TicketSaleCdcProcessor implements MessageProcessor {

    private final CdcReader reader;
    private final RuleEngine<TicketSaleRow> rules;
    private final ZoneId agencyZone;

    public TicketSaleCdcProcessor(CdcReader reader, RuleEngine<TicketSaleRow> rules, ZoneId agencyZone) {
        this.reader = reader;
        this.rules = rules;
        this.agencyZone = agencyZone;
    }

    @Override
    public EtlSource source() {
        return EtlSource.TICKETING_SALES;
    }

    @Override
    public WriteSet process(InboundMessage message, RuleContext context) {
        byte[] value = message.value();
        if (value == null) {
            return WriteSet.empty(message);
        }
        CdcReader.Read<TicketTransactionCdc> read = reader.read(value, TicketTransactionCdc.class);
        TicketTransactionCdc tx = read.value();
        BigDecimal amount;
        try {
            amount = new BigDecimal(tx.amount());
        } catch (NumberFormatException e) {
            throw new SchemaViolationException("amount: not a decimal");
        }
        LocalDate saleDate = LocalDate.ofInstant(tx.createdAt(), agencyZone);
        String hash = PayloadHasher.hashCdc(read.tree());
        TicketSaleRow row = new TicketSaleRow(
                saleDate,
                tx.transactionId(),
                tx.salePointId(),
                tx.routeId(),
                tx.stopId(),
                tx.ticketType(),
                tx.txnType(),
                amount,
                tx.currency(),
                tx.refundOf(),
                tx.status(),
                tx.isDelete(),
                tx.createdAt(),
                tx.updatedAt(),
                tx.lsn(),
                Instant.ofEpochMilli(tx.sourceTsMs()),
                hash,
                tx.op());
        rules.check(row, context);
        return WriteSet.ticketSale(message, hash, BusinessKey.ticketSale(saleDate, tx.transactionId()), row);
    }

    @Override
    public @Nullable String businessKey(InboundMessage message) {
        return BestEffortKeys.of(message, tree -> {
            String id = tree.path("transaction_id").asString(null);
            String created = tree.path("created_at").asString(null);
            if (id == null) {
                return null;
            }
            try {
                return created == null
                        ? id
                        : BusinessKey.ticketSale(
                                LocalDate.ofInstant(Instant.parse(created), agencyZone), UUID.fromString(id));
            } catch (RuntimeException e) {
                return id;
            }
        });
    }
}
