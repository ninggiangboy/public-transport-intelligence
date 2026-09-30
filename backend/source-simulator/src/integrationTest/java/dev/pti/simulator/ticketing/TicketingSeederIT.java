package dev.pti.simulator.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.pti.db.MigratedDatabases;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.rate.RateControl;
import dev.pti.testing.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DOC-25 T-13 against a migrated pg-source, as {@code source_simulator}: ten minutes of business time at
 * {@code rate=10} from 16:00 on a weekday, driven by a manual clock.
 */
class TicketingSeederIT {

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

    @AfterAll
    static void close() {
        dataSource.close();
    }

    /**
     * The whole-table checks below need an empty table: other ITs of this JVM share {@code ticketing_source}, and
     * {@code SimulatorMetricsIT} sells tickets at real time, so its rows fall outside this test's business window.
     */
    @BeforeEach
    void emptyTransactions() {
        jdbc.update("DELETE FROM public.ticket_transaction WHERE refund_of IS NOT NULL");
        jdbc.update("DELETE FROM public.ticket_transaction");
    }

    @Test
    void sellsAtTheConfiguredRateWithRefundsVoidsAndDeletes() {
        TestClock clock = TestClock.at(Instant.parse("2026-09-29T21:00:00Z")); // 16:00 CDT, λ = 2.0/s
        SalePointCatalog catalog =
                SalePointCatalog.build(Feeds.real(), TicketingConfiguration.referenceWeekday(Feeds.real()), 60);
        TicketingSettings settings = TicketingDefaults.settings();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketingSeeder seeder = new TicketingSeeder(
                clock,
                new SaleGenerator(catalog, settings, Feeds.real().zone(), 42),
                catalog,
                new TicketingRepository(jdbc),
                new RateControl(1.0, 10.0),
                settings,
                42,
                registry,
                new Throughput(() -> clock.realNow().toEpochMilli()));

        Instant start = clock.instant();
        seeder.tickUntil(clock.millis());
        for (int i = 0; i < 3_000; i++) {
            clock.advance(Duration.ofMillis(200));
            seeder.tickUntil(clock.millis());
        }

        assertThat(count("SELECT count(*) FROM public.sale_point")).isEqualTo(187);
        long sales = count("SELECT count(*) FROM public.ticket_transaction WHERE txn_type = 'SALE'")
                + (long) registry.counter("pti.sim.ticket.transactions", "txn_type", "SALE", "action", "delete")
                        .count();
        double expected = 2.0 * 10 * 600;
        assertThat(sales).isBetween((long) (expected * 0.85), (long) (expected * 1.15));
        assertThat(count("SELECT count(*) FROM public.ticket_transaction WHERE status = 'VOIDED'"))
                .isPositive();
        assertThat(count("""
                SELECT count(*) FROM public.ticket_transaction r
                LEFT JOIN public.ticket_transaction s ON s.transaction_id = r.refund_of
                WHERE r.txn_type = 'REFUND' AND (s.transaction_id IS NULL OR s.amount <> r.amount
                  OR s.sale_point_id <> r.sale_point_id OR r.created_at <= s.created_at)
                """)).as("every refund matches a real sale").isZero();
        assertThat(count("SELECT count(*) FROM public.ticket_transaction WHERE txn_type = 'REFUND'"))
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT min(created_at) >= ? AND max(created_at) <= ? FROM public.ticket_transaction",
                        Boolean.class,
                        Timestamp.from(start),
                        Timestamp.from(clock.instant())))
                .as("created_at is business time")
                .isTrue();
    }

    private static long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
