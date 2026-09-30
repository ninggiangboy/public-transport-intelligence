package dev.pti.api.transit.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.GeoPoint;
import dev.pti.api.transit.domain.GeometrySource;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.api.transit.domain.RouteSummary;
import dev.pti.apitest.TransitData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E-01 {@code GET /routes} and E-02 {@code GET /routes/{routeId}} (DOC-32 §3), through HTTP. */
class RouteControllerTest extends TransitWebTest {

    @BeforeEach
    void routes() {
        transit.routes.add(new RouteSummary(
                "18", "18", "Nicollet Av - Nicollet Mall - 1st Av", "18", 3, "0053A0", "FFFFFF", 18, 600));
        transit.routes.add(TransitData.route("901", 0, 1));
        transit.routes.add(new RouteSummary("77", null, null, "77", 3, null, null, null, null));
    }

    // ------------------------------------------------------------------------------------------------ E-01

    @Test
    @DisplayName("E-01 lists the routes with the feed version, cached for a minute, as fresh as the feed activation")
    void listRoutes() throws Exception {
        mvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-27T08:34:40Z"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.feedVersionId").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].routeId").value("18"))
                .andExpect(jsonPath("$.items[0].shortName").value("18"))
                .andExpect(jsonPath("$.items[0].longName").value("Nicollet Av - Nicollet Mall - 1st Av"))
                .andExpect(jsonPath("$.items[0].displayName").value("18"))
                .andExpect(jsonPath("$.items[0].routeType").value(3))
                .andExpect(jsonPath("$.items[0].color").value("0053A0"))
                .andExpect(jsonPath("$.items[0].textColor").value("FFFFFF"))
                .andExpect(jsonPath("$.items[0].sortOrder").value(18))
                .andExpect(jsonPath("$.items[0].typicalHeadwaySeconds").value(600));
    }

    @Test
    @DisplayName("AG-16 a route without a short name, colours or headway has no such members, not nulls")
    void nullMembersAreLeftOut() throws Exception {
        mvc.perform(get("/api/v1/routes"))
                .andExpect(jsonPath("$.items[2].routeId").value("77"))
                .andExpect(jsonPath("$.items[2].shortName").doesNotExist())
                .andExpect(jsonPath("$.items[2].longName").doesNotExist())
                .andExpect(jsonPath("$.items[2].color").doesNotExist())
                .andExpect(jsonPath("$.items[2].sortOrder").doesNotExist())
                .andExpect(jsonPath("$.items[2].typicalHeadwaySeconds").doesNotExist());
    }

    @Test
    @DisplayName("E-01 routeType filters, repeated or comma-separated alike")
    void routeTypeFilter() throws Exception {
        mvc.perform(get("/api/v1/routes").param("routeType", "0"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].routeId").value("901"));
        mvc.perform(get("/api/v1/routes").param("routeType", "0").param("routeType", "3"))
                .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/routes").param("routeType", "0,3"))
                .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/routes").param("routeType", "2"))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("E-01 a routeType that is not a number, or more than 20 of them, is a 400")
    void routeTypeValidation() throws Exception {
        mvc.perform(get("/api/v1/routes").param("routeType", "bus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:validation-error"));
        mvc.perform(get("/api/v1/routes").param("routeType", "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("routeType"));
    }

    @Test
    @DisplayName("AG-08 a parameter that the endpoint does not know is a 400 naming it")
    void unknownParameter() throws Exception {
        mvc.perform(get("/api/v1/routes").param("routeID", "18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("routeID"));
    }

    @Test
    @DisplayName("AG-19 without an ACTIVE feed /routes is a 503 with Retry-After 30")
    void noActiveFeed() throws Exception {
        transit.feed = null;

        mvc.perform(get("/api/v1/routes"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.detail").value("No active GTFS feed yet."));
    }

    // ------------------------------------------------------------------------------------------------ E-02

    private void routeDetail() {
        RouteSummary route = transit.routes.get(0);
        PatternStop stop = new PatternStop("51405", "51405", "Nicollet Ave & 46th St", 44.920401, -93.278012, 1);
        DirectionPattern north = new DirectionPattern(
                0,
                "NB",
                "Downtown Minneapolis",
                312,
                "180077",
                GeometrySource.SHAPE,
                List.of(new GeoPoint(-93.278123, 44.923411), new GeoPoint(-93.27809, 44.925002)),
                List.of(stop));
        DirectionPattern south = new DirectionPattern(
                1,
                null,
                null,
                290,
                null,
                GeometrySource.STOPS,
                List.of(new GeoPoint(-93.278012, 44.920401)),
                List.of());
        transit.details.put("18", new RouteDetail(3, route, List.of(north, south)));
    }

    @Test
    @DisplayName("E-02 a route with the GeoJSON line and the ordered stops of each direction, cached for five minutes")
    void getRoute() throws Exception {
        routeDetail();

        mvc.perform(get("/api/v1/routes/18"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-27T08:34:40Z"))
                .andExpect(jsonPath("$.routeId").value("18"))
                .andExpect(jsonPath("$.feedVersionId").value(3))
                .andExpect(jsonPath("$.displayName").value("18"))
                .andExpect(jsonPath("$.typicalHeadwaySeconds").value(600))
                .andExpect(jsonPath("$.directions.length()").value(2))
                .andExpect(jsonPath("$.directions[0].directionId").value(0))
                .andExpect(jsonPath("$.directions[0].label").value("NB"))
                .andExpect(jsonPath("$.directions[0].headsign").value("Downtown Minneapolis"))
                .andExpect(jsonPath("$.directions[0].tripCount").value(312))
                .andExpect(jsonPath("$.directions[0].shapeId").value("180077"))
                .andExpect(jsonPath("$.directions[0].geometrySource").value("SHAPE"))
                .andExpect(jsonPath("$.directions[0].geometry.type").value("LineString"))
                .andExpect(
                        jsonPath("$.directions[0].geometry.coordinates[0][0]").value(-93.278123))
                .andExpect(
                        jsonPath("$.directions[0].geometry.coordinates[0][1]").value(44.923411))
                .andExpect(jsonPath("$.directions[0].stops[0].stopId").value("51405"))
                .andExpect(jsonPath("$.directions[0].stops[0].stopSequence").value(1))
                .andExpect(jsonPath("$.directions[0].stops[0].lat").value(44.920401))
                .andExpect(jsonPath("$.directions[0].stops[0].lon").value(-93.278012))
                .andExpect(jsonPath("$.directions[1].label").doesNotExist())
                .andExpect(jsonPath("$.directions[1].shapeId").doesNotExist())
                .andExpect(jsonPath("$.directions[1].geometrySource").value("STOPS"))
                .andExpect(jsonPath("$.directions[1].stops").isEmpty());
    }

    @Test
    @DisplayName("E-02 a route that is not in the active feed is a 404 not-found")
    void unknownRoute() throws Exception {
        mvc.perform(get("/api/v1/routes/nope"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"));
    }

    @Test
    @DisplayName("E-02 takes no query parameters")
    void detailRejectsParameters() throws Exception {
        routeDetail();

        mvc.perform(get("/api/v1/routes/18").param("directionId", "0")).andExpect(status().isBadRequest());
    }
}
