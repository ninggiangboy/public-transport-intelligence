package dev.pti.simulator.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.pti.db.MigratedDatabases;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.scenario.RefundBurstScenario;
import dev.pti.simulator.scenario.ScenarioContext;
import dev.pti.simulator.scenario.ScenarioHooks;
import dev.pti.simulator.scenario.TicketSpikeScenario;
import dev.pti.testing.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DOC-25 T-14 for {@code ticket-spike} and {@code refund-burst} on a migrated pg-source: the statistical conditions
 * of DR-34 hold in the 15-minute windows of the source database. Thirty minutes of business time from 16:00 CDT on
 * Thursday 2026-10-01, with the regular seeder at rate 1.
 */
class TicketingScenariosIT {

    private static final Instant START = Instant.parse("2026-10-01T21:00:00Z");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MigratedDatabases.jdbcUrl("ticketing_source"));
        dataSource.setUsername("source_simulator");
        dataSource.setPassword(MigratedDatabases.password("source_simulator"));
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
    }

    /** Other tests count every transaction in this shared database: leave it as it was. */
    @AfterAll
    static void close() {
        Timestamp from = Timestamp.from(START);
        jdbc.update("DELETE FROM public.ticket_transaction WHERE refund_of IS NOT NULL AND created_at >= ?", from);
        jdbc.update("DELETE FROM public.ticket_transaction WHERE created_at >= ?", from);
        dataSource.close();
    }

    @Test
    void spikeAndBurstMeetTheAnomalyConditionsInEveryWindow() {
        TestClock clock = TestClock.at(START);
        SalePointCatalog catalog =
                SalePointCatalog.build(Feeds.real(), TicketingConfiguration.referenceWeekday(Feeds.real()), 60);
        TicketingSettings settings = TicketingDefaults.settings();
        ScenarioHooks hooks = new ScenarioHooks();
        TicketingSeeder seeder = new TicketingSeeder(
                clock,
                new SaleGenerator(catalog, settings, Feeds.real().zone(), 7),
                catalog,
                new TicketingRepository(jdbc),
                new RateControl(1.0, 1.0),
                settings,
                7,
                new SimpleMeterRegistry(),
                new Throughput(() -> clock.realNow().toEpochMilli()),
                hooks::ticketing);
        new TicketSpikeScenario(catalog)
                .start(context(hooks, 1), new TicketSpikeScenario.Params("KIOSK-005", 6.0, Duration.ofMinutes(30)));
        new RefundBurstScenario(catalog)
                .start(
                        context(hooks, 2),
                        new RefundBurstScenario.Params(
                                "KIOSK-007", 2.0, 0.8, Duration.ofMinutes(2), Duration.ofMinutes(30)));

        seeder.tickUntil(clock.millis());
        for (int i = 0; i < 9_000; i++) {
            clock.advance(Duration.ofMillis(200));
            seeder.tickUntil(clock.millis());
        }

        for (Map<String, Object> w : windows("KIOSK-005")) {
            assertThat(((Number) w.get("sales")).longValue()).as("spike %s", w).isGreaterThanOrEqualTo(20 + 60);
        }
        for (Map<String, Object> w : windows("KIOSK-007")) {
            long sales = ((Number) w.get("sales")).longValue();
            long refunds = ((Number) w.get("refunds")).longValue();
            assertThat(refunds).as("burst %s", w).isGreaterThanOrEqualTo(5);
            assertThat(refunds / (double) sales).as("burst %s", w).isGreaterThan(0.3);
        }
    }

    /** A fixed run id, so the overlays draw the same sales every time. */
    private static ScenarioContext context(ScenarioHooks hooks, long run) {
        return new ScenarioContext(new UUID(0x0192f7b12c3d7e4fL, run), hooks, 7, START.toEpochMilli());
    }

    /** The two whole 15-minute windows of the run at one sale point (DR-34). */
    private static List<Map<String, Object>> windows(String salePointId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT date_bin('15 minutes', created_at, TIMESTAMPTZ '2026-10-01 00:00:00+00') AS window_start,
                       count(*) FILTER (WHERE txn_type = 'SALE')   AS sales,
                       count(*) FILTER (WHERE txn_type = 'REFUND') AS refunds
                FROM public.ticket_transaction
                WHERE sale_point_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY 1 ORDER BY 1
                """, salePointId, Timestamp.from(START), Timestamp.from(START.plus(Duration.ofMinutes(30))));
        assertThat(rows).hasSize(2);
        return rows;
    }
}
