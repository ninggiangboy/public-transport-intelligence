package dev.pti.simulator.scenario;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** {@link ScenarioRuns} in memory, with the same rules as the table: a row ends once. */
final class InMemoryScenarioRuns implements ScenarioRuns {

    final Map<UUID, ScenarioRun> rows = new ConcurrentHashMap<>();
    final Map<UUID, String> experimentRunIds = new ConcurrentHashMap<>();

    @Override
    public void insert(ScenarioRun run, @Nullable String experimentRunId) {
        rows.put(run.runId(), run);
        if (experimentRunId != null) {
            experimentRunIds.put(run.runId(), experimentRunId);
        }
    }

    @Override
    public boolean finish(UUID runId, RunStatus status, Instant endedAt) {
        ScenarioRun row = rows.get(runId);
        if (row == null || row.status() != RunStatus.RUNNING) {
            return false;
        }
        rows.put(runId, ended(row, status, endedAt));
        return true;
    }

    @Override
    public int failRunning(Instant now) {
        int n = 0;
        for (ScenarioRun row : List.copyOf(rows.values())) {
            if (row.status() == RunStatus.RUNNING) {
                rows.put(row.runId(), ended(row, RunStatus.FAILED, now));
                n++;
            }
        }
        return n;
    }

    @Override
    public Optional<ScenarioRun> find(UUID runId) {
        return Optional.ofNullable(rows.get(runId));
    }

    @Override
    public List<ScenarioRun> list(@Nullable RunStatus status, int limit) {
        return rows.values().stream()
                .filter(r -> status == null || r.status() == status)
                .sorted(Comparator.comparing(ScenarioRun::startedAt)
                        .thenComparing(ScenarioRun::runId)
                        .reversed())
                .limit(limit)
                .toList();
    }

    RunStatus status(UUID runId) {
        return rows.get(runId).status();
    }

    private static ScenarioRun ended(ScenarioRun row, RunStatus status, Instant endedAt) {
        return new ScenarioRun(
                row.runId(),
                row.scenario(),
                status,
                row.params(),
                row.requestedBy(),
                row.startedAt(),
                row.plannedEndAt(),
                endedAt,
                null);
    }
}
