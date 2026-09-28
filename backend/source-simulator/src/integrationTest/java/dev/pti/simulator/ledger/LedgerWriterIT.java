package dev.pti.simulator.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.pti.db.MigratedDatabases;
import dev.pti.testing.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ledger writer against a migrated {@code pti_sim}, writing as {@code source_simulator} and reading back as
 * {@code experiment_runner} (DOC-25 §6.4).
 */
class LedgerWriterIT {

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate reader;
    private static HikariDataSource readerSource;

    @BeforeAll
    static void connect() {
        dataSource = pool("source_simulator");
        dataSource.addDataSourceProperty("reWriteBatchedInserts", "true");
        jdbc = new JdbcTemplate(dataSource);
        readerSource = pool("experiment_runner");
        reader = new JdbcTemplate(readerSource);
    }

    private static HikariDataSource pool(String role) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(MigratedDatabases.jdbcUrl("pti_sim"));
        ds.setUsername(role);
        ds.setPassword(MigratedDatabases.password(role));
        ds.setMaximumPoolSize(2);
        return ds;
    }

    @AfterAll
    static void close() {
        dataSource.close();
        readerSource.close();
    }

    @Test
    void writesEveryRecordedRowAndMaintainsPartitions() {
        TestClock clock = TestClock.atDefault();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LedgerWriter writer =
                new LedgerWriter(jdbc, clock, 1_000, 500, Duration.ofMillis(50), Duration.ofDays(2), registry);
        UUID resent = UUID.randomUUID();
        UUID run = UUID.randomUUID();

        writer.start();
        try {
            for (int i = 0; i < 1_234; i++) {
                writer.record(
                        entry(clock.realNow(), i % 100 == 0 ? resent : null, i % 7 == 0 ? run : null),
                        i % 2 == 0 ? "gtfs.vehicle_positions" : "gtfs.trip_updates",
                        i % 3,
                        i);
            }
        } finally {
            writer.stop();
        }

        assertThat(writer.queueDepth()).isZero();
        assertThat(writer.lastFlushAt()).isNotNull();
        assertThat(registry.get("pti.sim.ledger.rows").counter().count()).isEqualTo(1_234);
        Map<String, Object> summary = reader.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE is_resend) AS resends,
                       count(*) FILTER (WHERE scenario_run_id IS NOT NULL) AS scenario,
                       count(*) FILTER (WHERE intended_invalid) AS invalid,
                       max(kafka_offset) AS max_offset
                FROM sim.sim_ledger WHERE produced_at = ?
                """, clock.realNow().atOffset(ZoneOffset.UTC));
        assertThat(summary)
                .containsEntry("total", 1_234L)
                .containsEntry("resends", 13L)
                .containsEntry("scenario", 177L)
                .containsEntry("invalid", 0L)
                .containsEntry("max_offset", 1_233L);
        List<String> keys = reader.queryForList(
                "SELECT array_to_string(business_keys, '|') FROM sim.sim_ledger WHERE produced_at = ? LIMIT 1",
                String.class,
                clock.realNow().atOffset(ZoneOffset.UTC));
        assertThat(keys).containsExactly("v-1|2026-09-29T21:20:00Z");

        LocalDate today = LocalDate.ofInstant(clock.realNow(), ZoneOffset.UTC);
        List<String> partitions = reader.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'sim.sim_ledger'::regclass
                """, String.class);
        assertThat(partitions)
                .contains(
                        partition(today.minusDays(1)),
                        partition(today),
                        partition(today.plusDays(1)),
                        partition(today.plusDays(2)));
    }

    private static String partition(LocalDate day) {
        return "sim_ledger_p" + day.toString().replace("-", "");
    }

    private static LedgerEntry entry(Instant producedAt, @Nullable UUID resendOf, @Nullable UUID scenarioRunId) {
        return new LedgerEntry(
                UUID.randomUUID(),
                "VEHICLE_POSITION",
                List.of("v-1", "2026-09-29T21:20:00Z"),
                producedAt,
                producedAt,
                1,
                "0".repeat(64),
                null,
                resendOf,
                scenarioRunId);
    }
}
