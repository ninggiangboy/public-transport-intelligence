package dev.pti.etl.core.cdc;

import static dev.pti.etl.testing.EtlFixtures.context;
import static dev.pti.etl.testing.EtlFixtures.message;
import static dev.pti.etl.testing.EtlFixtures.salePoint;
import static dev.pti.etl.testing.EtlFixtures.ticketSale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.SalePointRow;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RuleEngine;
import dev.pti.etl.rules.TicketRules;
import dev.pti.etl.testing.EtlFixtures;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** DOC-20 §4.4 and the ticketing cases of DOC-16 §8. */
class CdcProcessorsTest {

    private final CdcReader reader =
            new CdcReader(Validation.buildDefaultValidatorFactory().getValidator());
    private final TicketSaleCdcProcessor sales = new TicketSaleCdcProcessor(
            reader, new RuleEngine<>(TicketRules.all(EtlFixtures.dq()), EtlFixtures.dq()), EtlFixtures.AGENCY_ZONE);
    private final SalePointCdcProcessor salePoints = new SalePointCdcProcessor(reader);

    private WriteSet sale(ObjectNode json) {
        return sales.process(message(EtlSource.TICKETING_SALES, json), context());
    }

    private DataException rejected(ObjectNode json) {
        Throwable thrown = catchThrowable(() -> sale(json));
        assertThat(thrown).isInstanceOf(DataException.class);
        return (DataException) thrown;
    }

    @Test
    void mapsASale() {
        WriteSet set = sale(ticketSale());

        TicketSaleRow row = set.ticketSales().getFirst();
        assertThat(row.saleDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(row.transactionId()).isEqualTo(UUID.fromString("01a0e9de-0000-7000-8000-000000000001"));
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(row.sourceLsn()).isEqualTo(32093984L);
        assertThat(row.eventTimestamp()).isEqualTo(Instant.ofEpochMilli(1790630340100L));
        assertThat(row.deleted()).isFalse();
        assertThat(row.isRefund()).isFalse();
        assertThat(set.version()).isEqualTo(32093984L);
        assertThat(set.businessKey()).isEqualTo("2026-09-29|01a0e9de-0000-7000-8000-000000000001");
    }

    @Test
    void theSaleDateIsTheAgencyDate() {
        ObjectNode json = ticketSale();
        json.put("created_at", "2026-09-30T03:00:00.000000Z");
        assertThat(sale(json).ticketSales().getFirst().saleDate()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void case04CustomerRefIsNeverRead() {
        ObjectNode without = ticketSale();
        without.remove("customer_ref");
        assertThat(sale(ticketSale()).messageHash()).isEqualTo(sale(without).messageHash());
    }

    @Test
    void theLsnAndSourceTimeAreNotPartOfTheHash() {
        ObjectNode later = ticketSale();
        later.put("__lsn", 99999999).put("__source_ts_ms", 1790630399999L);
        assertThat(sale(later).messageHash()).isEqualTo(sale(ticketSale()).messageHash());
    }

    @Test
    void aDeleteKeepsTheLastStateAndMarksIt() {
        ObjectNode json = ticketSale();
        json.put("__op", "d").put("__deleted", "true");
        assertThat(sale(json).ticketSales().getFirst().deleted()).isTrue();
    }

    @Test
    void case03UnknownFieldBreaksTheSchema() {
        ObjectNode json = ticketSale();
        json.put("foo", "bar");
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.SCHEMA);
        assertThat(e.ruleId()).isEqualTo("DQ-01");
    }

    @Test
    void anInvalidSalePointBreaksTheSchema() {
        ObjectNode json = ticketSale();
        json.put("sale_point_id", "SHOP-1");
        assertThat(rejected(json).getMessage()).contains("sale_point_id");
    }

    @Test
    void case20NegativeAmount() {
        ObjectNode json = ticketSale();
        json.put("amount", "-2.50");
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.QUALITY);
        assertThat(e.ruleId()).isEqualTo("DQ-10");
    }

    @Test
    void anAmountAboveTheLimitOrInAnotherCurrency() {
        ObjectNode big = ticketSale();
        big.put("amount", "600.00");
        assertThat(rejected(big).ruleId()).isEqualTo("DQ-10");
        ObjectNode euro = ticketSale();
        euro.put("currency", "EUR");
        assertThat(rejected(euro).ruleId()).isEqualTo("DQ-10");
    }

    @Test
    void case21RefundWithoutOriginal() {
        ObjectNode json = ticketSale();
        json.put("txn_type", "REFUND");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-11");
    }

    @Test
    void aSaleThatRefersToAnotherIsInconsistent() {
        ObjectNode json = ticketSale();
        json.put("refund_of", UUID.randomUUID().toString());
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-11");
    }

    @Test
    void malformedJsonIsADeserializationError() {
        Throwable thrown = catchThrowable(
                () -> sales.process(message(EtlSource.TICKETING_SALES, "{\"transaction_id\""), context()));
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        DataException.class, e -> assertThat(e.stage()).isEqualTo(DlqStage.DESERIALIZE));
    }

    @Test
    void aTombstoneWritesNothing() {
        assertThat(sales.process(EtlFixtures.tombstone(EtlSource.TICKETING_SALES), context())
                        .isEmpty())
                .isTrue();
        assertThat(salePoints
                        .process(EtlFixtures.tombstone(EtlSource.TICKETING_SALE_POINTS), context())
                        .isEmpty())
                .isTrue();
    }

    @Test
    void businessKeys() {
        assertThat(sales.businessKey(message(EtlSource.TICKETING_SALES, ticketSale())))
                .isEqualTo("2026-09-29|01a0e9de-0000-7000-8000-000000000001");
        assertThat(salePoints.businessKey(message(EtlSource.TICKETING_SALE_POINTS, salePoint())))
                .isEqualTo("KIOSK-053");
        assertThat(sales.businessKey(message(EtlSource.TICKETING_SALES, "[]"))).isNull();
    }

    @Test
    void mapsASalePoint() {
        WriteSet set = salePoints.process(message(EtlSource.TICKETING_SALE_POINTS, salePoint()), context());
        SalePointRow row = set.salePoints().getFirst();
        assertThat(row.salePointId()).isEqualTo("KIOSK-053");
        assertThat(row.kind()).isEqualTo("KIOSK");
        assertThat(row.sourceLsn()).isEqualTo(1200L);
        assertThat(row.deleted()).isFalse();
        assertThat(set.businessKey()).isEqualTo("KIOSK-053");
    }

    @Test
    void aDeletedSalePoint() {
        ObjectNode json = salePoint();
        json.put("__op", "d").put("__deleted", "true");
        assertThat(salePoints
                        .process(message(EtlSource.TICKETING_SALE_POINTS, json), context())
                        .salePoints()
                        .getFirst()
                        .deleted())
                .isTrue();
    }

    @Test
    void anInvalidSalePointKind() {
        ObjectNode json = salePoint();
        json.put("kind", "SHOP");
        Throwable thrown =
                catchThrowable(() -> salePoints.process(message(EtlSource.TICKETING_SALE_POINTS, json), context()));
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        DataException.class, e -> assertThat(e.stage()).isEqualTo(DlqStage.SCHEMA));
    }

    @Test
    void edgeCasesOfTicketSales() {
        assertThat(sales.source()).isEqualTo(dev.pti.etl.core.EtlSource.TICKETING_SALES);
        assertThat(sales.process(
                                dev.pti.etl.testing.EtlFixtures.tombstone(dev.pti.etl.core.EtlSource.TICKETING_SALES),
                                context())
                        .ticketSales())
                .isEmpty();
        assertThat(catchThrowable(() -> sales.process(
                        message(
                                dev.pti.etl.core.EtlSource.TICKETING_SALES,
                                ticketSale().put("amount", "abc")),
                        context())))
                .isInstanceOf(dev.pti.common.error.SchemaViolationException.class)
                .hasMessageContaining("amount");
        assertThat(sales.businessKey(message(dev.pti.etl.core.EtlSource.TICKETING_SALES, "{\"x\":1}")))
                .isNull();
        assertThat(sales.businessKey(
                        message(dev.pti.etl.core.EtlSource.TICKETING_SALES, "{\"transaction_id\":\"t-1\"}")))
                .isEqualTo("t-1");
        assertThat(sales.businessKey(message(
                        dev.pti.etl.core.EtlSource.TICKETING_SALES,
                        "{\"transaction_id\":\"t-1\",\"created_at\":\"yesterday\"}")))
                .isEqualTo("t-1");
    }
}
