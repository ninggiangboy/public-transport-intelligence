package dev.pti.simulator.control;

import dev.pti.simulator.scenario.RunStatus;
import dev.pti.simulator.scenario.ScenarioCatalog;
import dev.pti.simulator.scenario.ScenarioEngine;
import dev.pti.simulator.scenario.ScenarioException;
import dev.pti.simulator.scenario.ScenarioRun;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The scenario endpoints of the control API (DOC-25 §8). */
@RestController
@RequestMapping("/sim")
public class ScenarioController {

    static final String REQUESTED_BY = "X-Requested-By";

    static final int DEFAULT_LIMIT = 20;

    static final int MAX_LIMIT = 100;

    private final ScenarioEngine engine;
    private final ScenarioCatalog catalog;

    public ScenarioController(ScenarioEngine engine, ScenarioCatalog catalog) {
        this.engine = engine;
        this.catalog = catalog;
    }

    @GetMapping("/scenarios")
    public ScenarioCatalog.Catalog scenarios() {
        return catalog.describe(engine.scenarios());
    }

    @PostMapping("/scenarios/{name}")
    public ResponseEntity<ScenarioRun> start(
            @PathVariable String name,
            @RequestBody(required = false) @Nullable JsonNode body,
            @RequestHeader(name = REQUESTED_BY, required = false) @Nullable String requestedBy) {
        ScenarioRun run = engine.start(name, body, requestedBy);
        return ResponseEntity.created(URI.create("/sim/scenario-runs/" + run.runId()))
                .body(run);
    }

    @DeleteMapping("/scenarios/{name}")
    public ResponseEntity<Void> stopScenario(@PathVariable String name) {
        engine.stopScenario(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scenario-runs")
    public Runs runs(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw ScenarioException.invalid("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return new Runs(engine.runs(status(status), n));
    }

    @GetMapping("/scenario-runs/{runId}")
    public ScenarioRun run(@PathVariable String runId) {
        return engine.run(runId(runId));
    }

    @DeleteMapping("/scenario-runs/{runId}")
    public ResponseEntity<Void> stopRun(@PathVariable String runId) {
        engine.stopRun(runId(runId));
        return ResponseEntity.noContent().build();
    }

    private static @Nullable RunStatus status(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ScenarioException.invalid("status", "must be one of RUNNING, COMPLETED, STOPPED, FAILED");
        }
    }

    private static UUID runId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ScenarioException(
                    ScenarioException.Problem.SCENARIO_RUN_NOT_FOUND, "There is no scenario run " + value + ".");
        }
    }

    /** The body of {@code GET /sim/scenario-runs}. */
    public record Runs(List<ScenarioRun> items) {}
}
