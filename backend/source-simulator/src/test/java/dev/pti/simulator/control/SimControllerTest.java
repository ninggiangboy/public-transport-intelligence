package dev.pti.simulator.control;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.simulator.Throughput;
import dev.pti.simulator.emit.EmitterHarness;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.ledger.Ledger;
import dev.pti.simulator.ledger.LedgerEntry;
import dev.pti.simulator.scenario.ScenarioEngine;
import dev.pti.simulator.scenario.ScenarioEngines;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code GET /sim/status} and {@code PUT /sim/rate} (DOC-25 §8, part of T-18). */
class SimControllerTest {

    private static final Instant START = Instant.parse("2026-09-29T21:30:00Z"); // 16:30 CDT

    private EmitterHarness harness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Feed feed = Feeds.mini();
        harness = new EmitterHarness(feed, START, 42, 1.0).run(Duration.ofSeconds(10));
        ServiceDays days =
                new ServiceDays(feed, new ServiceDateMapper(feed.calendar(), "auto"), Duration.ofMinutes(10));
        Throughput throughput = new Throughput(() -> harness.clock().realNow().toEpochMilli());
        ScenarioEngine engine = ScenarioEngines.idle(harness);
        StatusService service = new StatusService(
                harness.clock(), feed, days, harness.rate(), harness.emitter(), throughput, new IdleLedger(), engine);
        mvc = MockMvcBuilders.standaloneSetup(new SimController(service, harness.rate(), engine))
                .setControllerAdvice(new ProblemHandler())
                .build();
    }

    @Test
    void statusShowsClockFeedRateAndActivity() throws Exception {
        mvc.perform(get("/sim/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clock.businessNow").value("2026-09-29T21:30:10Z"))
                .andExpect(jsonPath("$.clock.offset").value("PT0S"))
                .andExpect(jsonPath("$.clock.agencyTimeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.clock.serviceDates[1].realDate").value("2026-09-29"))
                .andExpect(jsonPath("$.clock.serviceDates[1].feedDate").value("2026-09-29"))
                .andExpect(jsonPath("$.feed.validFrom").exists())
                .andExpect(jsonPath("$.rate.gtfsRt").value(1.0))
                .andExpect(jsonPath("$.activeVehicles").value(harness.emitter().activeVehicles()))
                .andExpect(jsonPath("$.messagesPerSecond['gtfs.vehicle_positions']")
                        .value(0.0))
                .andExpect(jsonPath("$.ledger.queueDepth").value(0))
                .andExpect(jsonPath("$.runningScenarios").isEmpty());
    }

    @Test
    void setsOneMultiplierAndKeepsTheOther() throws Exception {
        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON).content("{\"gtfsRt\": 2.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate.gtfsRt").value(2.5))
                .andExpect(jsonPath("$.rate.ticketing").value(1.0));

        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON).content("{\"ticketing\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate.gtfsRt").value(2.5))
                .andExpect(jsonPath("$.rate.ticketing").value(0.0));
    }

    @Test
    void rejectsAnEmptyBody() throws Exception {
        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-param"))
                .andExpect(jsonPath("$.title").value("Invalid parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/sim/rate"));

        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-param"));
    }

    @Test
    void rejectsOutOfRangeValuesPerFieldAndChangesNothing() throws Exception {
        mvc.perform(put("/sim/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gtfsRt\": 25, \"ticketing\": 0.05}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("gtfsRt"))
                .andExpect(jsonPath("$.errors[0].message").value(SimController.OUT_OF_RANGE))
                .andExpect(jsonPath("$.errors[1].field").value("ticketing"));

        mvc.perform(get("/sim/status")).andExpect(jsonPath("$.rate.gtfsRt").value(1.0));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(put("/sim/rate").contentType(MediaType.APPLICATION_JSON).content("{\"gtfsRt\": \"fast\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-param"));
    }

    private static final class IdleLedger implements Ledger {

        @Override
        public void record(LedgerEntry entry, String topic, int partition, long offset) {}

        @Override
        public int queueDepth() {
            return 0;
        }

        @Override
        public @Nullable Instant lastFlushAt() {
            return null;
        }
    }
}
