package dev.pti.api.platform.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform through HTTP: Problem Details for every kind of error (DOC-30 §3, DOC-31 §9), the token checks (SEC-02,
 * SEC-03, SEC-09, SEC-10), keyset paging (AG-02…AG-04), the headers of every response and the rejection of unknown
 * parameters and fields (AG-08, AG-17). The endpoints under test are the stand-ins of {@code StubEndpoints}.
 */
class PlatformWebTest extends ApiWebTestSupport {

    private static final String PROBLEM = "application/problem+json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    private static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------------------------------ Problem Details

    @Test
    @DisplayName("AG-01 a data response and an error both carry a 32-hex X-Trace-Id, and the error body repeats it")
    void traceIdOnDataAndErrors() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));

        MvcResult error = mvc.perform(get("/api/v1/stops/stub-missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andReturn();
        JsonNode problem = body(error);
        assertThat(problem.path("type").asString()).isEqualTo("urn:pti:problem:not-found");
        assertThat(problem.path("title").asString()).isEqualTo("Resource not found");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("detail").asString()).isEqualTo("The stop does not exist.");
        assertThat(problem.path("instance").asString()).isEqualTo("/api/v1/stops/stub-missing");
        assertThat(problem.path("traceId").asString())
                .isEqualTo(error.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("SEC-10 an unexpected exception is a bare 500 with the trace id and no class name or message")
    void internalErrorLeaksNothing() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/stops/stub-boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andReturn();

        String text = result.getResponse().getContentAsString();
        assertThat(text).doesNotContain("NullPointerException", "secret internal detail", "dev.pti", "at ");
        JsonNode problem = JSON.readTree(text);
        assertThat(problem.path("type").asString()).isEqualTo("urn:pti:problem:internal-error");
        assertThat(problem.path("detail").asString())
                .isEqualTo("An unexpected error occurred. Quote the trace id when reporting.");
        assertThat(problem.path("traceId").asString()).hasSize(32);
        assertThat(problem.has("errors")).isFalse();
    }

    @Test
    @DisplayName("E-07 a statement timeout is 503 service-unavailable with Retry-After: 5")
    void transientInfrastructureIsServiceUnavailable() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:service-unavailable"));
    }

    @Test
    @DisplayName("A path that nothing serves is 404 not-found")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list/extra").header("Authorization", viewer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"));
    }

    @Test
    @DisplayName("A method that the matrix does not declare is refused before routing: 401 anonymous, 403 otherwise")
    void undeclaredMethodIsDenied() throws Exception {
        mvc.perform(post("/api/v1/stops/stub-list")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/stops/stub-list").header("Authorization", operator()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An Accept the API cannot satisfy is 406 not-acceptable")
    void notAcceptable() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-acceptable"));
    }

    @Test
    @DisplayName("A body of another media type is 415 unsupported-media-type")
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", operator())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:unsupported-media-type"));
    }

    @Test
    @DisplayName("A body longer than 1 MiB is 413 payload-too-large before it is read")
    void payloadTooLarge() throws Exception {
        byte[] large = new byte[1024 * 1024 + 1];
        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(large))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:payload-too-large"));
    }

    // ---------------------------------------------------------------------------------------------- validation

    @ParameterizedTest(name = "AG-04 limit={0} is a 400 on the field limit")
    @MethodSource("badLimits")
    void limitOutOfRange(String limit) throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].message").value("must be between 1 and 500"));
    }

    static Stream<String> badLimits() {
        return Stream.of("0", "501", "-1");
    }

    @Test
    @DisplayName("A limit that is not a number is a 400 on the field limit")
    void limitNotANumber() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    @DisplayName("AG-08 a parameter the handler does not declare is a 400 that names it")
    void unknownParameterIsRejected() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").param("routeID", "18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("routeID"));
    }

    @Test
    @DisplayName("Declared parameters pass, in either form of a multi-valued filter")
    void declaredParametersPass() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").param("status", "NEW,MANUAL").param("limit", "5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/stops/stub-list").param("status", "NEW", "MANUAL"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A missing required parameter is a 400 on that parameter")
    void missingParameter() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("needed"));
    }

    @Test
    @DisplayName("A parameter of the wrong type is a 400 on that parameter")
    void parameterTypeMismatch() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-type").param("count", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("count"));
    }

    @Test
    @DisplayName("DOC-31 §9 a malformed path id is a 404, not a 400")
    void malformedPathIdIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/stops/n-abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mvc.perform(get("/api/v1/stops/n-42"))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
    }

    @Test
    @DisplayName("AG-17 a body field the request does not know is a 400 that points at it")
    void unknownBodyField() throws Exception {
        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"ok\", \"extra\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("/extra"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a known property"));
    }

    @Test
    @DisplayName("A body that fails Bean Validation is a 400 on the field")
    void beanValidation() throws Exception {
        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
    }

    @Test
    @DisplayName("A body that is not JSON is a 400 with a fixed sentence")
    void brokenJson() throws Exception {
        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is not valid JSON of the expected shape."));
    }

    @Test
    @DisplayName("A request body nested deeper than 64 levels is refused")
    void deeplyNestedBody() throws Exception {
        String deep = "[".repeat(100) + "]".repeat(100);
        mvc.perform(put("/api/v1/etl/flags/stub-flag")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": " + deep + "}"))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------------------------------------- paging

    @Test
    @DisplayName("AG-02 120 rows at limit=50 come as 50, 50 and 20, with no duplicate and no gap")
    void keysetPaging() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        List<Integer> sizes = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            var request = get("/api/v1/stops/stub-list").param("limit", "50");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode response =
                    body(mvc.perform(request).andExpect(status().isOk()).andReturn());
            response.path("items").forEach(item -> seen.add(item.asString()));
            sizes.add(response.path("items").size());
            cursor = response.has("nextCursor") ? response.path("nextCursor").asString() : null;
            if (cursor == null) {
                break;
            }
        }

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(seen).hasSize(120).doesNotHaveDuplicates().isSorted();
        assertThat(seen.get(119)).isEqualTo("119");
    }

    @Test
    @DisplayName("AG-03 the cursor of status=NEW used with status=MANUAL is a 400 on the field cursor")
    void cursorOfAnotherQuery() throws Exception {
        JsonNode first = body(mvc.perform(
                        get("/api/v1/stops/stub-list").param("status", "NEW").param("limit", "10"))
                .andReturn());
        String cursor = first.path("nextCursor").asString();

        mvc.perform(get("/api/v1/stops/stub-list").param("status", "MANUAL").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
        mvc.perform(get("/api/v1/stops/stub-list").param("status", "NEW").param("cursor", cursor))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A cursor that is not a cursor is a 400 on the field cursor")
    void garbageCursor() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").param("cursor", "%%%not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    // -------------------------------------------------------------------------------------------- security

    @ParameterizedTest(name = "SEC-02 {0} is a 401 even on a public endpoint")
    @MethodSource("badTokens")
    void badTokensAreRefused(String description, String token) throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").header("Authorization", JwtFixture.bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:unauthorized"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> badTokens() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "an expired token", JwtFixture.token().expired().build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "a token signed by another key",
                        JwtFixture.token().signedByAnotherKey().build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "a token of another issuer",
                        JwtFixture.token()
                                .issuer("http://evil.example/realms/pti")
                                .build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "a token without the audience",
                        JwtFixture.token().audience().build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "a token for another audience",
                        JwtFixture.token().audience("someone-else").build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "an alg=none token", JwtFixture.token().unsigned().build()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "an HS256 token", JwtFixture.token().hs256().build()),
                org.junit.jupiter.params.provider.Arguments.of("garbage", "not.a.jwt"));
    }

    @Test
    @DisplayName("A valid token on a public endpoint is accepted")
    void validTokenOnPublicEndpoint() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").header("Authorization", viewer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-03 a handler missing from the matrix is closed: 401 anonymous, 403 with a token")
    void undeclaredHandlerIsDenied() throws Exception {
        mvc.perform(get("/api/v1/undeclared-stub")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/undeclared-stub").header("Authorization", viewer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/undeclared-stub").header("Authorization", operator()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A path without any handler is 401 for anonymous and a plain 404 for an authenticated caller")
    void pathWithoutHandler() throws Exception {
        mvc.perform(get("/api/v1/nothing-here")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/nothing-here").header("Authorization", viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:not-found"));
    }

    @Test
    @DisplayName("Paths outside /api and /internal are denied")
    void otherPathsAreDenied() throws Exception {
        mvc.perform(get("/something-else")).andExpect(status().isUnauthorized());
        mvc.perform(post("/something-else")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-09 a preflight from another origin gets no Access-Control-Allow-Origin")
    void noCors() throws Exception {
        MvcResult result = mvc.perform(options("/api/v1/stops/stub-list")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isNull();
    }

    @Test
    @DisplayName("A caller parameter carries the username as actor and the effective roles")
    void callerArgument() throws Exception {
        mvc.perform(get("/api/v1/insights/bunching/stub-caller").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("user:viewer"))
                .andExpect(jsonPath("$.operator").value(false));
        mvc.perform(get("/api/v1/insights/bunching/stub-caller").header("Authorization", operator()))
                .andExpect(jsonPath("$.actor").value("user:operator"))
                .andExpect(jsonPath("$.operator").value(true));
    }

    @Test
    @DisplayName("Roles the API does not know are ignored")
    void unknownRolesGiveNoAccess() throws Exception {
        String token = JwtFixture.token()
                .username("guest")
                .roles("offline_access", "admin")
                .build();

        mvc.perform(get("/api/v1/insights/bunching/stub-caller").header("Authorization", JwtFixture.bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-06 the webhook needs its token: none 401, wrong length 401, wrong value 401, right one 204")
    void webhookToken() throws Exception {
        mvc.perform(post("/internal/alerts/alertmanager")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/alerts/alertmanager").header("Authorization", "Bearer short"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:unauthorized"));
        mvc.perform(post("/internal/alerts/alertmanager")
                        .header("Authorization", "Bearer " + "x".repeat(WEBHOOK_TOKEN.length())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/alerts/alertmanager").header("Authorization", "Bearer " + WEBHOOK_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("The webhook token does not open the public API, and a user token does not open the webhook")
    void tokensAreNotInterchangeable() throws Exception {
        mvc.perform(get("/api/v1/stops/stub-list").header("Authorization", "Bearer " + WEBHOOK_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/alerts/alertmanager").header("Authorization", operator()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Other paths under /internal are refused even with the token")
    void internalPathsNeedARule() throws Exception {
        mvc.perform(get("/internal/anything").header("Authorization", "Bearer " + WEBHOOK_TOKEN))
                .andExpect(status().isForbidden());
    }
}
