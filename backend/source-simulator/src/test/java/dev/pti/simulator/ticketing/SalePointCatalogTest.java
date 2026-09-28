package dev.pti.simulator.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.simulator.feed.Feeds;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SalePointCatalogTest {

    private static final SalePointCatalog CATALOG =
            SalePointCatalog.build(Feeds.real(), TicketingConfiguration.referenceWeekday(Feeds.real()), 60);

    @Test
    void placesKiosksByTheDeparturesOfTheFirstTuesdayOfTheFeed() {
        assertThat(TicketingConfiguration.referenceWeekday(Feeds.real())).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    /** DOC-25 §9.1: 60 kiosks, one onboard validator per bus route, three app channels. */
    @Test
    void hasTheDocumentedSalePoints() {
        Map<SalePoint.Kind, Long> counts = new EnumMap<>(SalePoint.Kind.class);
        CATALOG.all().forEach(p -> counts.merge(p.kind(), 1L, Long::sum));

        assertThat(counts)
                .containsEntry(SalePoint.Kind.KIOSK, 60L)
                .containsEntry(SalePoint.Kind.ONBOARD, 124L)
                .containsEntry(SalePoint.Kind.APP, 3L);
        assertThat(CATALOG.all().getFirst().id()).isEqualTo("KIOSK-001");
        assertThat(CATALOG.all().getFirst().name()).startsWith("Kiosk – ");
    }

    /** The CHECK constraints of {@code sale_point} (V1__ticketing_schema.sql). */
    @Test
    void satisfiesTheTableConstraints() {
        assertThat(CATALOG.all()).allSatisfy(p -> {
            assertThat(p.id()).matches("^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$");
            assertThat(p.name()).hasSizeBetween(1, 200);
            if (p.kind() == SalePoint.Kind.KIOSK) {
                assertThat(p.stopId()).isNotNull();
            }
            if (p.kind() == SalePoint.Kind.ONBOARD) {
                assertThat(p.routeId()).isNotNull();
            }
        });
    }

    @Test
    void picksKioskOnboardAndAppInTheDocumentedShares() {
        SplittableRandom rng = new SplittableRandom(1);
        Map<SalePoint.Kind, Integer> counts = new EnumMap<>(SalePoint.Kind.class);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            counts.merge(CATALOG.pick(rng).kind(), 1, Integer::sum);
        }

        assertThat(counts.get(SalePoint.Kind.KIOSK) / (double) n).isBetween(0.44, 0.46);
        assertThat(counts.get(SalePoint.Kind.ONBOARD) / (double) n).isBetween(0.34, 0.36);
        assertThat(counts.get(SalePoint.Kind.APP) / (double) n).isBetween(0.19, 0.21);
    }
}
