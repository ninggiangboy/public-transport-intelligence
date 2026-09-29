package dev.pti.simulator.scenario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** {@code sim.sim_scenario_run} (DOC-13 §6.3), written as {@code source_simulator}. Every call autocommits. */
public final class JdbcScenarioRuns implements ScenarioRuns {

    private static final String COLUMNS =
            "run_id, scenario, params::text AS params, status, requested_by, started_at, planned_end_at, ended_at";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcScenarioRuns(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void insert(ScenarioRun run, @Nullable String experimentRunId) {
        jdbc.update(
                """
                INSERT INTO sim.sim_scenario_run
                  (run_id, scenario, params, status, requested_by, experiment_run_id, started_at, planned_end_at)
                VALUES (?, ?, ?::jsonb, 'RUNNING', ?, ?, ?, ?)
                """,
                run.runId(),
                run.scenario(),
                mapper.writeValueAsString(run.params()),
                run.requestedBy(),
                experimentRunId,
                utc(run.startedAt()),
                run.plannedEndAt() == null ? null : utc(run.plannedEndAt()));
    }

    @Override
    public boolean finish(UUID runId, RunStatus status, Instant endedAt) {
        return jdbc.update("""
                        UPDATE sim.sim_scenario_run SET status = ?, ended_at = GREATEST(?, started_at)
                        WHERE run_id = ? AND status = 'RUNNING'
                        """, status.name(), utc(endedAt), runId) == 1;
    }

    @Override
    public int failRunning(Instant now) {
        return jdbc.update("""
                UPDATE sim.sim_scenario_run SET status = 'FAILED', ended_at = GREATEST(?, started_at)
                WHERE status = 'RUNNING'
                """, utc(now));
    }

    @Override
    public Optional<ScenarioRun> find(UUID runId) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM sim.sim_scenario_run WHERE run_id = ?", this::map, runId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ScenarioRun> list(@Nullable RunStatus status, int limit) {
        if (status == null) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM sim.sim_scenario_run ORDER BY started_at DESC, run_id DESC LIMIT ?",
                    this::map,
                    limit);
        }
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM sim.sim_scenario_run WHERE status = ? ORDER BY started_at DESC, run_id DESC LIMIT ?",
                this::map,
                status.name(),
                limit);
    }

    private ScenarioRun map(ResultSet rs, int row) throws SQLException {
        return new ScenarioRun(
                rs.getObject("run_id", UUID.class),
                rs.getString("scenario"),
                RunStatus.valueOf(rs.getString("status")),
                mapper.readTree(rs.getString("params")),
                rs.getString("requested_by"),
                instant(rs, "started_at"),
                instant(rs, "planned_end_at"),
                instant(rs, "ended_at"),
                null);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
