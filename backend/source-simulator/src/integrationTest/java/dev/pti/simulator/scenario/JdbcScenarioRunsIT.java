package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.pti.db.MigratedDatabases;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sim.sim_scenario_run} on a migrated {@code pti_sim}, written as {@code source_simulator} (DOC-13 §6.3,
 * DOC-25 §7.1 and T-16: rows a previous process left {@code RUNNING} are marked {@code FAILED} at startup).
 */
class JdbcScenarioRunsIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MigratedDatabases.jdbcUrl("pti_sim"));
        dataSource.setUsername("source_simulator");
        dataSource.setPassword(MigratedDatabases.password("source_simulator"));
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @Test
    void recordsARunAndItsEndOnce() {
        JdbcScenarioRuns runs = new JdbcScenarioRuns(jdbc, MAPPER);
        Instant start = Instant.parse("2026-10-02T10:15:03.120Z");
        ScenarioRun run = run(start, "experiment:EXP-03/20261002T101500Z-r07");

        runs.insert(run, "EXP-03/20261002T101500Z-r07");
        ScenarioRun stored = runs.find(run.runId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(stored.params()).isEqualTo(run.params());
        assertThat(stored.startedAt()).isEqualTo(start);
        assertThat(stored.plannedEndAt()).isEqualTo(start.plus(Duration.ofMinutes(10)));
        assertThat(stored.endedAt()).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT experiment_run_id FROM sim.sim_scenario_run WHERE run_id = ?",
                        String.class,
                        run.runId()))
                .isEqualTo("EXP-03/20261002T101500Z-r07");

        assertThat(runs.finish(run.runId(), RunStatus.STOPPED, start.plusSeconds(30)))
                .isTrue();
        assertThat(runs.finish(run.runId(), RunStatus.COMPLETED, start.plusSeconds(60)))
                .isFalse();

        ScenarioRun ended = runs.find(run.runId()).orElseThrow();
        assertThat(ended.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(ended.endedAt()).isEqualTo(start.plusSeconds(30));
        assertThat(runs.list(RunStatus.STOPPED, 100))
                .extracting(ScenarioRun::runId)
                .contains(run.runId());
        assertThat(runs.list(RunStatus.RUNNING, 100))
                .extracting(ScenarioRun::runId)
                .doesNotContain(run.runId());
    }

    @Test
    void marksRowsLeftRunningAsFailedAtStartup() {
        JdbcScenarioRuns runs = new JdbcScenarioRuns(jdbc, MAPPER);
        Instant start = Instant.parse("2026-10-03T08:00:00Z");
        ScenarioRun left = run(start, "cli");
        runs.insert(left, null);

        // The clock of the new process may be behind the old one: ended_at never goes before started_at.
        assertThat(runs.failRunning(start.minusSeconds(5))).isPositive();

        ScenarioRun failed = runs.find(left.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.endedAt()).isEqualTo(start);
        assertThat(runs.failRunning(start.plusSeconds(5))).isZero();
    }

    @Test
    void listsNewestFirst() {
        JdbcScenarioRuns runs = new JdbcScenarioRuns(jdbc, MAPPER);
        ScenarioRun older = run(Instant.parse("2026-10-04T08:00:00Z"), "cli");
        ScenarioRun newer = run(Instant.parse("2026-10-04T09:00:00Z"), "cli");
        runs.insert(older, null);
        runs.insert(newer, null);

        assertThat(runs.list(null, 2)).extracting(ScenarioRun::runId).containsExactly(newer.runId(), older.runId());
    }

    private static ScenarioRun run(Instant start, String requestedBy) {
        return new ScenarioRun(
                UUID.randomUUID(),
                "bad-data",
                RunStatus.RUNNING,
                MAPPER.readTree("{\"ratio\": 0.05, \"kinds\": [\"malformed_json\"], \"duration\": \"PT10M\"}"),
                requestedBy,
                start,
                start.plus(Duration.ofMinutes(10)),
                null,
                null);
    }
}
