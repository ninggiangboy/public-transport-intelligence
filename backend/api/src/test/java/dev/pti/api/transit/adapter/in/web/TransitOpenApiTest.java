package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The transit endpoints in the OpenAPI document (DOC-31 §12, DOC-32 §2): operation ids, examples, security, errors. */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class TransitOpenApiTest extends TransitWebTest {

    private static final Map<String, String> OPERATIONS = Map.of(
            "/api/v1/routes", "listRoutes",
            "/api/v1/routes/{routeId}", "getRoute",
            "/api/v1/routes/{routeId}/delays", "getRouteDelays",
            "/api/v1/routes/{routeId}/delay-profile", "getRouteDelayProfile",
            "/api/v1/vehicles/live", "listLiveVehicles",
            "/api/v1/stops", "searchStops",
            "/api/v1/stops/{stopId}", "getStop",
            "/api/v1/stops/{stopId}/arrivals", "listStopArrivals");

    @Autowired
    private JsonMapper mapper;

    private JsonNode operation(String path) throws Exception {
        JsonNode document = mapper.readTree(
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        return document.path("paths").path(path).path("get");
    }

    @Test
    @DisplayName("E-01 to E-08 have the operation ids of DOC-32 and an example of the 200 response")
    void operationIdsAndExamples() throws Exception {
        for (Map.Entry<String, String> entry : OPERATIONS.entrySet()) {
            JsonNode operation = operation(entry.getKey());

            assertThat(operation.path("operationId").asString())
                    .as(entry.getKey())
                    .isEqualTo(entry.getValue());
            assertThat(operation.path("summary").asString()).as(entry.getKey()).isNotBlank();
            assertThat(operation
                            .path("responses")
                            .path("200")
                            .path("content")
                            .path("*/*")
                            .path("examples")
                            .size())
                    .as(entry.getKey() + " has an example")
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Only E-03 asks for a token; the lookups by id document their 404, the others their 400")
    void securityAndErrors() throws Exception {
        assertThat(operation("/api/v1/routes/{routeId}/delays").path("security").isArray())
                .isTrue();
        for (String path :
                List.of("/api/v1/routes", "/api/v1/routes/{routeId}", "/api/v1/vehicles/live", "/api/v1/stops")) {
            assertThat(operation(path).path("security").isMissingNode())
                    .as(path)
                    .isTrue();
        }
        for (String path : List.of(
                "/api/v1/routes/{routeId}",
                "/api/v1/routes/{routeId}/delays",
                "/api/v1/routes/{routeId}/delay-profile",
                "/api/v1/stops/{stopId}",
                "/api/v1/stops/{stopId}/arrivals")) {
            assertThat(operation(path).path("responses").has("404")).as(path).isTrue();
        }
        for (String path : List.of("/api/v1/routes", "/api/v1/stops", "/api/v1/vehicles/live")) {
            assertThat(operation(path).path("responses").has("400")).as(path).isTrue();
        }
    }
}
