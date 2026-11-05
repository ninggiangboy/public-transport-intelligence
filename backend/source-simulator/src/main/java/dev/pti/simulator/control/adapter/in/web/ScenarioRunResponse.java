package dev.pti.simulator.control.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.pti.simulator.scenario.domain.RunStatus;
import dev.pti.simulator.scenario.domain.ScenarioRun;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code POST /sim/scenarios/{name}}, {@code GET /sim/scenario-runs} and
 * {@code GET /sim/scenario-runs/{runId}} (DOC-25 §8). Null fields are left out, unlike the domain record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioRunResponse(
        UUID runId,
        String scenario,
        RunStatus status,
        Map<String, Object> params,
        String requestedBy,
        Instant startedAt,
        @Nullable Instant plannedEndAt,
        @Nullable Instant endedAt,
        @Nullable Map<String, Object> progress) {

    static ScenarioRunResponse of(ScenarioRun run) {
        return new ScenarioRunResponse(
                run.runId(),
                run.scenario(),
                run.status(),
                run.params(),
                run.requestedBy(),
                run.startedAt(),
                run.plannedEndAt(),
                run.endedAt(),
                run.progress());
    }
}
