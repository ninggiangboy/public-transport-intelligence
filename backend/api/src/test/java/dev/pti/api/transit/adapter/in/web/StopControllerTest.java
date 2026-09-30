package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopRouteRef;
import dev.pti.api.transit.domain.StopRoutes;
import dev.pti.apitest.TransitData;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** E-06 {@code GET /stops} and E-07 {@code GET /stops/{stopId}} (DOC-32 §3), through HTTP. */
class StopControllerTest extends TransitWebTest {

    @org.springframework.beans.factory.annotation.Autowired
    private JsonMapper mapper;

    @BeforeEach
    void network() {
        transit.routes.add(TransitData.route("18", 3, 18));
        transit.routes.add(TransitData.route("901", 0, 1));
        transit.stops.put("100", TransitData.stop("100", "Lake St & Nicollet", -93.27, 44.94));
        transit.stops.put("200", TransitData.stop("200", "Nicollet Ave & 46th", -93.28, 44.92));
        transit.stops.put("300", TransitData.stop("300", "Airport Terminal", -93.21, 44.88));
        transit.stopRoutes = new StopRoutes(Map.of(
                "100", List.of(new StopRouteRef("18", List.of("Downtown"))),
                "200",
                        List.of(
                                new StopRouteRef("18", List.of("Downtown", "Nicollet & 66th")),
                                new StopRouteRef("901", List.of("Mall of America"))),
                "300", List.of(new StopRouteRef("901", List.of()))));
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    // ------------------------------------------------------------------------------------------------ E-06

    @Test
    @DisplayName("EP-07 q: the exact stop code first; the items carry the routes; no cursor, cached for a minute")
    void textSearch() throws Exception {
        transit.stops.put("Nicollet", TransitData.stop("Nicollet", "Zulu Stop", -93.0, 44.0));

        mvc.perform(get("/api/v1/stops").param("q", "Nicollet"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-27T08:34:40Z"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].stopId").value("Nicollet"))
                .andExpect(jsonPath("$.items[1].stopId").value("200"))
                .andExpect(jsonPath("$.items[1].code").value("200"))
                .andExpect(jsonPath("$.items[1].name").value("Nicollet Ave & 46th"))
                .andExpect(jsonPath("$.items[1].lat").value(44.92))
                .andExpect(jsonPath("$.items[1].lon").value(-93.28))
                .andExpect(jsonPath("$.items[1].locationType").value(0))
                .andExpect(jsonPath("$.items[1].wheelchairBoarding").value(1))
                .andExpect(jsonPath("$.items[1].routeIds[0]").value("18"))
                .andExpect(jsonPath("$.items[1].routeIds[1]").value("901"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("E-06 q is trimmed and must be 2-100 characters; its limit is 1-50 and it takes no cursor")
    void textValidation() throws Exception {
        mvc.perform(get("/api/v1/stops").param("q", "  ni  ")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/stops").param("q", "n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        mvc.perform(get("/api/v1/stops").param("q", "  n  ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("q", "x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("q", "x".repeat(100))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("cursor", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    @DisplayName("E-06 exactly one of q, or bbox and/or routeId: none or both is a 400")
    void oneMode() throws Exception {
        mvc.perform(get("/api/v1/stops")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("routeId", "18"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("bbox", "-93.3,44.9,-93.2,45.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("EP-08 a bbox of one degree by one degree, and one that is malformed or inverted, are 400s")
    void bboxValidation() throws Exception {
        for (String bad : new String[] {
            "-94.0,44.0,-93.0,45.0",
            "1,2,3",
            "a,b,c,d",
            "-93.2,44.9,-93.3,45.0",
            "-200,44,-93,45",
            "-93.3,44.9,-93.2,95"
        }) {
            mvc.perform(get("/api/v1/stops").param("bbox", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("bbox"));
        }
        mvc.perform(get("/api/v1/stops").param("bbox", "-93.5,44.5,-93.0,45.0")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AG-02 bbox pages by stop id, limit then limit then the rest, and the last page has no cursor")
    void bboxPaging() throws Exception {
        String bbox = "-93.30,44.80,-93.10,45.00";

        JsonNode first = body(get("/api/v1/stops").param("bbox", bbox).param("limit", "1"));
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("items").get(0).path("stopId").asString()).isEqualTo("100");
        String cursor = first.path("nextCursor").asString();

        JsonNode second = body(
                get("/api/v1/stops").param("bbox", bbox).param("limit", "1").param("cursor", cursor));
        assertThat(second.path("items").get(0).path("stopId").asString()).isEqualTo("200");

        JsonNode last = body(get("/api/v1/stops")
                .param("bbox", bbox)
                .param("limit", "5")
                .param("cursor", second.path("nextCursor").asString()));
        assertThat(last.path("items")).hasSize(1);
        assertThat(last.path("items").get(0).path("stopId").asString()).isEqualTo("300");
        assertThat(last.has("nextCursor")).isFalse();
    }

    @Test
    @DisplayName("AG-03 a cursor of one query used for another is a 400 on the field cursor")
    void cursorBelongsToItsQuery() throws Exception {
        JsonNode first = body(
                get("/api/v1/stops").param("bbox", "-93.30,44.80,-93.10,45.00").param("limit", "1"));

        mvc.perform(get("/api/v1/stops")
                        .param("bbox", "-93.30,44.80,-93.10,45.00")
                        .param("routeId", "901")
                        .param("cursor", first.path("nextCursor").asString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    @DisplayName("AG-04 limit 501 and 0 are 400s in the area mode; the default is 200")
    void areaLimit() throws Exception {
        mvc.perform(get("/api/v1/stops").param("routeId", "901").param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(get("/api/v1/stops").param("routeId", "901").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stops").param("routeId", "901").param("limit", "500"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E-06 routeId lists the stops of the route; an unknown route has none")
    void routeFilter() throws Exception {
        mvc.perform(get("/api/v1/stops").param("routeId", "901"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].stopId").value("200"))
                .andExpect(jsonPath("$.items[1].stopId").value("300"));
        mvc.perform(get("/api/v1/stops").param("routeId", "nope"))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/stops").param("routeId", " ")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AG-08 a parameter that the endpoint does not know is a 400 naming it")
    void unknownParameter() throws Exception {
        mvc.perform(get("/api/v1/stops").param("q", "ni").param("routeID", "18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("routeID"));
    }

    // ------------------------------------------------------------------------------------------------ E-07

    @Test
    @DisplayName("E-07 a stop with its routes in route-list order, headsigns, colours and the open disruptions")
    void stopDetail() throws Exception {
        transit.disruptions.add(new StopDisruption(
                "alert-1",
                "disruption-1",
                "18",
                0,
                1,
                "Delays on route 18 northbound",
                Instant.parse("2026-09-29T20:58:00Z")));
        transit.disruptions.add(new StopDisruption("alert-2", null, "901", null, 0, "Slow", null));

        mvc.perform(get("/api/v1/stops/200"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Vary", "Authorization"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-27T08:34:40Z"))
                .andExpect(jsonPath("$.stopId").value("200"))
                .andExpect(jsonPath("$.name").value("Nicollet Ave & 46th"))
                .andExpect(jsonPath("$.locationType").value(0))
                .andExpect(jsonPath("$.wheelchairBoarding").value(1))
                .andExpect(jsonPath("$.routes.length()").value(2))
                .andExpect(jsonPath("$.routes[0].routeId").value("18"))
                .andExpect(jsonPath("$.routes[0].displayName").value("18"))
                .andExpect(jsonPath("$.routes[0].color").value("0053A0"))
                .andExpect(jsonPath("$.routes[0].textColor").value("FFFFFF"))
                .andExpect(jsonPath("$.routes[0].headsigns[1]").value("Nicollet & 66th"))
                .andExpect(jsonPath("$.routes[1].routeId").value("901"))
                .andExpect(jsonPath("$.activeDisruptions.length()").value(2))
                .andExpect(jsonPath("$.activeDisruptions[0].alertId").value("alert-1"))
                .andExpect(jsonPath("$.activeDisruptions[0].disruptionId").value("disruption-1"))
                .andExpect(jsonPath("$.activeDisruptions[0].routeId").value("18"))
                .andExpect(jsonPath("$.activeDisruptions[0].directionId").value(0))
                .andExpect(jsonPath("$.activeDisruptions[0].severity").value(1))
                .andExpect(jsonPath("$.activeDisruptions[0].title").value("Delays on route 18 northbound"))
                .andExpect(jsonPath("$.activeDisruptions[0].startedAt").value("2026-09-29T20:58:00Z"))
                .andExpect(jsonPath("$.activeDisruptions[1].disruptionId").doesNotExist())
                .andExpect(jsonPath("$.activeDisruptions[1].directionId").doesNotExist())
                .andExpect(jsonPath("$.activeDisruptions[1].startedAt").doesNotExist());
    }

    @Test
    @DisplayName("EP-09 anonymous asks for PUBLIC disruptions only, a viewer and an operator for all of them")
    void disruptionAudience() throws Exception {
        mvc.perform(get("/api/v1/stops/200")).andExpect(status().isOk());
        assertThat(transit.lastAudiences).containsExactly(Audience.PUBLIC);

        mvc.perform(get("/api/v1/stops/200").header("Authorization", viewer())).andExpect(status().isOk());
        assertThat(transit.lastAudiences).containsExactlyInAnyOrder(Audience.values());

        transit.lastAudiences = null;
        mvc.perform(get("/api/v1/stops/200").header("Authorization", operator()))
                .andExpect(status().isOk());
        assertThat(transit.lastAudiences).containsExactlyInAnyOrder(Audience.values());
    }

    @Test
    @DisplayName("E-07 a stop that is not in the active feed is a 404; parameters are not accepted")
    void unknownStop() throws Exception {
        mvc.perform(get("/api/v1/stops/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"));
        mvc.perform(get("/api/v1/stops/200").param("x", "1")).andExpect(status().isBadRequest());
    }
}
