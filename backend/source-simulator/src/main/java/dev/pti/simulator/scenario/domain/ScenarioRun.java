package dev.pti.simulator.scenario.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One scenario run as the API shows it (DOC-25 §8): a row of {@code sim.sim_scenario_run}, plus the progress
 * counters while it runs. Times are real time; {@code params} and {@code progress} hold plain JSON values, and the
 * adapters serialize them.
 */
public record ScenarioRun(
        UUID runId,
        String scenario,
        RunStatus status,
        Map<String, Object> params,
        String requestedBy,
        Instant startedAt,
        @Nullable Instant plannedEndAt,
        @Nullable Instant endedAt,
        @Nullable Map<String, Object> progress) {

    public ScenarioRun withProgress(@Nullable Map<String, Object> counters) {
        return new ScenarioRun(
                runId, scenario, status, params, requestedBy, startedAt, plannedEndAt, endedAt, counters);
    }
}
