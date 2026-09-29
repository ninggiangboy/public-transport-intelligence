package dev.pti.simulator.scenario;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The history of scenario runs, {@code sim.sim_scenario_run} (DOC-13 §6.3). */
public interface ScenarioRuns {

    /** Inserts a new {@code RUNNING} row. */
    void insert(ScenarioRun run, @Nullable String experimentRunId);

    /** Ends a running row; a row that has already ended keeps its status. */
    boolean finish(UUID runId, RunStatus status, Instant endedAt);

    /** At startup: runs live in memory only, so every row still {@code RUNNING} failed with the last process. */
    int failRunning(Instant now);

    Optional<ScenarioRun> find(UUID runId);

    /** Newest first. */
    List<ScenarioRun> list(@Nullable RunStatus status, int limit);
}
