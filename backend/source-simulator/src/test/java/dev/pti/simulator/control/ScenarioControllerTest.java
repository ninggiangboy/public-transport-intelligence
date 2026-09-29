package dev.pti.simulator.control;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.simulator.scenario.ScenarioFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/** The scenario endpoints of DOC-25 §8 (T-15): 201/400/404/409, {@code X-Requested-By}, idempotent deletes. */
class ScenarioControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ScenarioFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = ScenarioFixture.onMiniFeed();
        mvc = fixture.mockMvc();
    }

    @Test
    void startsARunAndPointsToIt() throws Exception {
        MvcResult result = mvc.perform(post("/sim/scenarios/bad-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-By", "experiment:EXP-03/20261002T101500Z-r07")
                        .content("{\"ratio\": 0.05, \"kinds\": [\"malformed_json\", \"unknown_route\"],"
                                + " \"duration\": \"PT10M\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/sim/scenario-runs/")))
                .andExpect(jsonPath("$.scenario").value("bad-data"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.params.ratio").value(0.05))
                .andExpect(jsonPath("$.params.kinds[1]").value("unknown_route"))
                .andExpect(jsonPath("$.params.entityTypes", hasSize(2)))
                .andExpect(jsonPath("$.params.duration").value("PT10M"))
                .andExpect(jsonPath("$.requestedBy").value("experiment:EXP-03/20261002T101500Z-r07"))
                .andExpect(jsonPath("$.startedAt").value("2026-09-29T21:20:00Z"))
                .andExpect(jsonPath("$.plannedEndAt").value("2026-09-29T21:30:00Z"))
                .andReturn();
        String runId = MAPPER.readTree(result.getResponse().getContentAsString())
                .get("runId")
                .asString();

        mvc.perform(get("/sim/scenario-runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progress.corrupted.malformed_json").value(0));
        mvc.perform(get("/sim/scenario-runs").param("status", "running"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].runId").value(runId));
        mvc.perform(get("/sim/status"))
                .andExpect(jsonPath("$.runningScenarios[0].runId").value(runId))
                .andExpect(jsonPath("$.runningScenarios[0].scenario").value("bad-data"));
    }

    @Test
    void usesTheDefaultsForAnEmptyBody() throws Exception {
        mvc.perform(post("/sim/scenarios/duplicates"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.params.ratio").value(0.1))
                .andExpect(jsonPath("$.params.maxDelay").value("PT1M"))
                .andExpect(jsonPath("$.requestedBy").value("cli"));
    }

    @Test
    void answersInvalidParamPerField() throws Exception {
        mvc.perform(post("/sim/scenarios/bad-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratio\": 0.9, \"colour\": \"red\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-param"))
                .andExpect(jsonPath("$.title").value("Invalid parameter"))
                .andExpect(jsonPath("$.errors[0].field").value("colour"));
        mvc.perform(post("/sim/scenarios/bad-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratio\": 0.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ratio"))
                .andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 0.5"));
        mvc.perform(post("/sim/scenarios/bad-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratio\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-param"));
    }

    @Test
    void answersNotFoundForUnknownScenariosAndRuns() throws Exception {
        mvc.perform(post("/sim/scenarios/earthquake"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:unknown-scenario"))
                .andExpect(jsonPath("$.title").value("Unknown scenario"));
        mvc.perform(delete("/sim/scenarios/earthquake")).andExpect(status().isNotFound());
        mvc.perform(get("/sim/scenario-runs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:scenario-run-not-found"));
        mvc.perform(get("/sim/scenario-runs/not-a-uuid")).andExpect(status().isNotFound());
        mvc.perform(delete("/sim/scenario-runs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void answersConflictForASecondSingleRun() throws Exception {
        mvc.perform(post("/sim/scenarios/bad-data")).andExpect(status().isCreated());

        mvc.perform(post("/sim/scenarios/bad-data"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:scenario-conflict"))
                .andExpect(jsonPath("$.title").value("Scenario conflict"));
    }

    @Test
    void answersConflictWhenNoVehiclesCanBePaired() throws Exception {
        ScenarioFixture night = ScenarioFixture.onMiniFeedAt("2026-09-30T08:00:00Z");

        night.mockMvc()
                .perform(post("/sim/scenarios/bunching")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\": \"18\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:no-eligible-vehicles"));
    }

    @Test
    void deletesAreIdempotent() throws Exception {
        MvcResult result = mvc.perform(post("/sim/scenarios/bad-data")).andReturn();
        String runId = MAPPER.readTree(result.getResponse().getContentAsString())
                .get("runId")
                .asString();

        mvc.perform(delete("/sim/scenario-runs/" + runId)).andExpect(status().isNoContent());
        mvc.perform(delete("/sim/scenario-runs/" + runId)).andExpect(status().isNoContent());
        mvc.perform(delete("/sim/scenarios/bad-data")).andExpect(status().isNoContent());
        mvc.perform(get("/sim/scenario-runs/" + runId))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.endedAt").value("2026-09-29T21:20:00Z"))
                .andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void refusesToSetTheRateWhileALoadRampRuns() throws Exception {
        mvc.perform(post("/sim/scenarios/load-ramp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"steps\": [3]}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON).content("{\"gtfsRt\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:load-ramp-running"))
                .andExpect(jsonPath("$.title").value("Load ramp running"));
        mvc.perform(get("/sim/status")).andExpect(jsonPath("$.rate.gtfsRt").value(3.0));
    }

    @Test
    void checksTheListParameters() throws Exception {
        mvc.perform(get("/sim/scenario-runs").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(get("/sim/scenario-runs").param("status", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }
}
