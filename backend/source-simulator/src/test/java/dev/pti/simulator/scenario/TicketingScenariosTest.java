package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.ticketing.SaleGenerator;
import dev.pti.simulator.ticketing.SalePointCatalog;
import dev.pti.simulator.ticketing.TicketingActions;
import dev.pti.simulator.ticketing.TicketingDefaults;
import dev.pti.simulator.ticketing.TicketingOverlay;
import dev.pti.simulator.ticketing.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DOC-25 §7.6 and §7.7 without a database: the overlays driven like the seeder drives them. */
class TicketingScenariosTest {

    private static final Instant START = Instant.parse("2026-09-29T21:00:00Z");

    @Test
    void aSpikeSellsAtItsRateAtOneSalePoint() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-002\", \"extraPerMinute\": 12, \"duration\": \"PT15M\"}");
        FakeActions actions = new FakeActions(kit.catalog);

        drive(kit, actions, Duration.ofMinutes(15));

        assertThat(actions.inserted).allSatisfy(t -> assertThat(t.salePointId()).isEqualTo("KIOSK-002"));
        assertThat(actions.inserted.size()).isBetween(150, 210);
        assertThat(actions.inserted).allSatisfy(t -> assertThat(t.isRefund()).isFalse());
    }

    @Test
    void aSpikeSellsNothingWhileTicketingIsPausedAndDetachesWhenStopped() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("ticket-spike", "{}");
        FakeActions actions = new FakeActions(kit.catalog);
        actions.paused = true;

        drive(kit, actions, Duration.ofMinutes(5));
        assertThat(actions.inserted).isEmpty();

        kit.engine.stopRun(run.runId());
        assertThat(kit.hooks.ticketing()).isEmpty();
    }

    @Test
    void aBurstRefundsRealSalesAfterTheDelayAndFinishesItsRefundsAfterTheRun() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start(
                "refund-burst",
                "{\"salePointId\": \"KIOSK-003\", \"salesPerMinute\": 6, \"refundRatio\": 0.8, \"refundDelay\": \"PT2M\","
                        + " \"duration\": \"PT15M\"}");
        FakeActions actions = new FakeActions(kit.catalog);

        drive(kit, actions, Duration.ofMinutes(15));
        kit.engine.stopRun(run.runId());
        assertThat(kit.hooks.attached(run.runId())).as("pending refunds").isTrue();
        long salesAtStop = actions.inserted.stream().filter(t -> !t.isRefund()).count();
        drive(kit, actions, Duration.ofMinutes(3));

        assertThat(kit.hooks.attached(run.runId())).isFalse();
        Map<UUID, Transaction> sales = new HashMap<>();
        List<Transaction> refunds = new ArrayList<>();
        for (Transaction t : actions.inserted) {
            if (t.isRefund()) {
                refunds.add(t);
            } else {
                sales.put(t.id(), t);
            }
        }
        assertThat(sales).hasSize((int) salesAtStop);
        assertThat(refunds.size() / (double) sales.size()).isBetween(0.65, 0.95);
        for (Transaction refund : refunds) {
            Transaction sale = sales.get(refund.refundOf());
            assertThat(sale).isNotNull();
            assertThat(Duration.between(sale.createdAt(), refund.createdAt())).isEqualTo(Duration.ofMinutes(2));
            assertThat(refund.amount()).isEqualTo(sale.amount());
            assertThat(refund.salePointId()).isEqualTo("KIOSK-003");
        }
    }

    @Test
    void rejectsAnUnknownSalePointAndATooShortDuration() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-999\", \"duration\": \"PT5M\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("duration"));
        assertThatThrownBy(() -> kit.start("refund-burst", "{\"salePointId\": \"KIOSK-999\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .containsExactly(
                                        new ScenarioException.FieldError("salePointId", "unknown sale point")));
    }

    @Test
    void twoSpikesMayRunAtDifferentSalePointsButNotAtTheSameOne() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-001\"}");
        kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-002\"}");

        assertThatThrownBy(() -> kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-001\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.problem()).isEqualTo(ScenarioException.Problem.SCENARIO_CONFLICT));
    }

    /** Ticks the ticketing overlays every 200 ms of business time, like the seeder. */
    private static void drive(ScenarioKit kit, FakeActions actions, Duration duration) {
        long end = kit.harness.clock().millis() + duration.toMillis();
        while (kit.harness.clock().millis() < end) {
            long from = kit.harness.clock().millis();
            kit.harness.clock().advance(Duration.ofMillis(200));
            for (TicketingOverlay overlay : kit.hooks.ticketing()) {
                overlay.onTick(actions, from, kit.harness.clock().millis());
            }
            kit.engine.tick();
        }
    }

    private static final class FakeActions implements TicketingActions {

        final List<Transaction> inserted = new ArrayList<>();
        final SalePointCatalog catalog;
        final SaleGenerator generator;
        boolean paused;

        FakeActions(SalePointCatalog catalog) {
            this.catalog = catalog;
            this.generator = new SaleGenerator(
                    catalog, TicketingDefaults.settings(), Feeds.mini().zone(), 42);
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public SaleGenerator generator() {
            return generator;
        }

        @Override
        public SalePointCatalog catalog() {
            return catalog;
        }

        @Override
        public void insert(Transaction transaction) {
            inserted.add(transaction);
        }
    }
}
