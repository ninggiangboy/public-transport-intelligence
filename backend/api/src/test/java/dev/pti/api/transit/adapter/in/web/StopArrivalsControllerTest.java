package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.apitest.TransitData;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E-08 {@code GET /stops/{stopId}/arrivals} (DOC-32 §3), through HTTP. */
class StopArrivalsControllerTest extends TransitWebTest {

    private Instant now;

    @BeforeEach
    void stop() {
        now = clock.instant();
        transit.stops.put("51405", TransitData.stop("51405", "Nicollet Ave & 46th St", -93.278012, 44.920401));
        probe(null, null, Instant.parse("2026-09-29T21:05:12Z"));
    }

    @Test
    @DisplayName("E-08 upcoming calls with the scheduled and predicted time, the delay, the samples and the confidence")
    void shape() throws Exception {
        transit.candidates.add(TransitData.withHistory(
                TransitData.candidate("27371245-AUG26-MVS-BUS-Weekday-01", now.plusSeconds(300)), "64.4", 36));
        transit.candidates.add(TransitData.candidate("27371302-AUG26-MVS-BUS-Weekday-01", now.plusSeconds(900)));

        mvc.perform(get("/api/v1/stops/51405/arrivals"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-29T21:05:12Z"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.stopId").value("51405"))
                .andExpect(jsonPath("$.businessNow").isString())
                .andExpect(jsonPath("$.realtimeEnabled").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].tripId").value("27371245-AUG26-MVS-BUS-Weekday-01"))
                .andExpect(jsonPath("$.items[0].routeId").value("18"))
                .andExpect(jsonPath("$.items[0].directionId").value(0))
                .andExpect(jsonPath("$.items[0].headsign").value("Downtown Minneapolis"))
                .andExpect(jsonPath("$.items[0].serviceDate").value("2026-09-29"))
                .andExpect(jsonPath("$.items[0].scheduledArrival").isString())
                .andExpect(jsonPath("$.items[0].predictedArrival").isString())
                .andExpect(jsonPath("$.items[0].predictedDelaySeconds").value(64))
                .andExpect(jsonPath("$.items[0].sampleCount").value(36))
                .andExpect(jsonPath("$.items[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.items[0].realtimeArrival").doesNotExist())
                .andExpect(jsonPath("$.items[1].predictedDelaySeconds").value(0))
                .andExpect(jsonPath("$.items[1].sampleCount").value(0))
                .andExpect(jsonPath("$.items[1].confidence").value("NONE"));
    }

    @Test
    @DisplayName("E-08 no trips is an empty list")
    void empty() throws Exception {
        mvc.perform(get("/api/v1/stops/51405/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("E-08 the defaults are a 90 minute horizon; the horizon parameter reaches the query")
    void horizon() throws Exception {
        mvc.perform(get("/api/v1/stops/51405/arrivals")).andExpect(status().isOk());
        assertThat(transit.lastArrivalHorizon).isEqualTo(Duration.ofMinutes(90));

        mvc.perform(get("/api/v1/stops/51405/arrivals").param("horizon", "PT2H").param("limit", "5"))
                .andExpect(status().isOk());
        assertThat(transit.lastArrivalHorizon).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("E-08 a horizon that is not a duration or outside PT15M..PT3H, and a limit outside 1..30, are 400s")
    void validation() throws Exception {
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("horizon", "90"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("horizon"));
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("horizon", "PT10M"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("horizon"));
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("horizon", "PT4H"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("limit", "31")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("limit", "30").param("horizon", "PT3H"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("limit", "many")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E-08 a stop that is not in the active feed is a 404")
    void unknownStop() throws Exception {
        mvc.perform(get("/api/v1/stops/nope/arrivals"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"));
    }

    @Test
    @DisplayName("AG-08 a parameter that the endpoint does not know is a 400 naming it")
    void unknownParameter() throws Exception {
        mvc.perform(get("/api/v1/stops/51405/arrivals").param("count", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("count"));
    }
}
