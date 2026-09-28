package dev.pti.simulator.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.simulator.feed.Feeds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SaleGeneratorTest {

    private static final SalePointCatalog CATALOG =
            SalePointCatalog.build(Feeds.mini(), Feeds.mini().validFrom(), 5);
    private static final SaleGenerator GENERATOR = new SaleGenerator(
            CATALOG, TicketingDefaults.settings(), Feeds.mini().zone(), 42);

    /** 16:30 CDT on a Tuesday: peak, 2 sales per second. */
    private static final Instant TUESDAY_PEAK = Instant.parse("2026-09-29T21:30:00Z");

    @Test
    void followsTheHourlyProfileWithTheWeekendFactor() {
        assertThat(GENERATOR.rate(TUESDAY_PEAK)).isEqualTo(2.0);
        assertThat(GENERATOR.rate(Instant.parse("2026-10-03T21:30:00Z"))).isEqualTo(1.2);
        assertThat(GENERATOR.rate(Instant.parse("2026-11-26T22:30:00Z")))
                .as("Thanksgiving counts as a Sunday; 16:30 CST")
                .isEqualTo(1.2);
        assertThat(GENERATOR.rate(Instant.parse("2026-09-29T08:30:00Z"))).isEqualTo(0.05);
    }

    @Test
    void chargesThePeakSingleFareOnWeekdayPeaksOnly() {
        assertThat(GENERATOR.amount(TicketType.SINGLE, TUESDAY_PEAK)).isEqualByComparingTo("2.50");
        assertThat(GENERATOR.amount(TicketType.SINGLE, Instant.parse("2026-09-29T17:00:00Z")))
                .isEqualByComparingTo("2.00");
        assertThat(GENERATOR.amount(TicketType.SINGLE, Instant.parse("2026-10-03T21:30:00Z")))
                .isEqualByComparingTo("2.00");
        assertThat(GENERATOR.amount(TicketType.MONTH, TUESDAY_PEAK)).isEqualByComparingTo("76.00");
    }

    @Test
    void fillsTheColumnsByKindOfSalePoint() {
        SplittableRandom rng = new SplittableRandom(3);
        int cash = 0;
        int counter = 0;
        for (int i = 0; i < 20_000; i++) {
            Transaction t = GENERATOR.sale(rng, TUESDAY_PEAK);
            assertThat(t.createdAt()).isEqualTo(TUESDAY_PEAK);
            assertThat(t.refundOf()).isNull();
            assertThat(t.amount()).isGreaterThan(BigDecimal.ZERO);
            switch (t.salePointId().substring(0, t.salePointId().indexOf('-'))) {
                case "KIOSK" -> {
                    assertThat(t.stopId()).isNotNull();
                    assertThat(t.routeId()).isNull();
                }
                case "ONBOARD" -> {
                    assertThat(t.routeId()).isEqualTo(t.salePointId().substring("ONBOARD-".length()));
                    assertThat(t.stopId()).isNull();
                }
                default -> {
                    assertThat(t.routeId()).isNull();
                    assertThat(t.stopId()).isNull();
                    assertThat(t.customerRef()).isNotNull();
                }
            }
            if (!t.salePointId().startsWith("APP-")) {
                counter++;
                if (t.customerRef() == null) {
                    cash++;
                }
            }
            if (t.customerRef() != null) {
                assertThat(t.customerRef()).matches("cust-[0-9a-f]{8}");
            }
        }
        assertThat(cash / (double) counter).isBetween(0.37, 0.43);
    }
}
