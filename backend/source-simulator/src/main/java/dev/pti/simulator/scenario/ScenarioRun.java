package dev.pti.simulator.scenario;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One scenario run as the API shows it (DOC-25 §8): a row of {@code sim.sim_scenario_run}, plus the progress
 * counters while it runs. Times are real time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioRun(
        UUID runId,
        String scenario,
        RunStatus status,
        JsonNode params,
        String requestedBy,
        Instant startedAt,
        @Nullable Instant plannedEndAt,
        @Nullable Instant endedAt,
        @Nullable Map<String, Object> progress) {

    ScenarioRun withProgress(@Nullable Map<String, Object> counters) {
        return new ScenarioRun(
                runId, scenario, status, params, requestedBy, startedAt, plannedEndAt, endedAt, counters);
    }
}
