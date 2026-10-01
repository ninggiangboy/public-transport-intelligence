package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import dev.pti.apitest.InMemoryAlerts;
import dev.pti.apitest.InMemoryInsight;
import dev.pti.apitest.RecordingUiEvents;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the web tests of the insight and alert endpoints share: the in-memory ports, reset before each test, the
 * business clock to place rows in the default range of a request, the token of each role, and the freshness probe
 * result that {@code X-Data-As-Of} is read from. The subclasses hold the locks of the state they change
 * ({@code in-memory-insight}, {@code in-memory-alerts}, {@code freshness-probe-result}), because the test classes run
 * concurrently over one context.
 */
public abstract class InsightWebSupport extends ApiWebTestSupport {

    /** The instant the probe reports for each source of the insight endpoints. */
    protected static final Instant VEHICLE_POSITION_AT = Instant.parse("2026-09-29T21:19:30Z");

    protected static final Instant TRIP_UPDATE_AT = Instant.parse("2026-09-29T21:19:25Z");
    protected static final Instant SALES_AT = Instant.parse("2026-09-29T21:18:00Z");
    protected static final Instant OTP_AT = Instant.parse("2026-09-29T08:00:41Z");

    @Autowired
    protected InMemoryInsight insight;

    @Autowired
    protected InMemoryAlerts alerts;

    @Autowired
    protected RecordingUiEvents uiEvents;

    @Autowired
    protected BusinessClock clock;

    @Autowired
    protected JsonMapper json;

    @Autowired
    private FreshnessSnapshots snapshots;

    @Autowired
    private ApiCaches caches;

    @BeforeEach
    void resetFakes() {
        insight.reset();
        alerts.reset();
        uiEvents.clear();
        caches.cache("freshness").invalidateAll();
        caches.cache("public-disruptions").invalidateAll();
        caches.cache("otp").invalidateAll();
        probe();
    }

    /** Stores a probe result with the instants above, so that the use cases can name their as-of. */
    protected void probe() {
        snapshots.store(new FreshnessSnapshot(
                clock.instant(),
                InMemoryInsight.feed(),
                Map.of(
                        SourceKind.GTFS_RT_VEHICLE_POSITION, VEHICLE_POSITION_AT,
                        SourceKind.GTFS_RT_TRIP_UPDATE, TRIP_UPDATE_AT,
                        SourceKind.TICKETING_SALES, SALES_AT),
                Instant.parse("2026-09-29T21:05:12Z"),
                clock.instant(),
                OTP_AT,
                false));
    }

    protected static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    protected static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }

    protected static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", bearer);
    }

    protected JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    protected JsonNode okBody(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    /** The field names of the errors of a 400 Problem. */
    protected void assertValidationError(MockHttpServletResponse response, String field) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = body(response);
        assertThat(problem.path("type").asString()).isEqualTo("urn:pti:problem:validation-error");
        assertThat(problem.path("errors"))
                .extracting(error -> error.path("field").asString())
                .contains(field);
    }

    protected void assertProblem(MockHttpServletResponse response, int status, String slug) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body(response).path("type").asString()).isEqualTo("urn:pti:problem:" + slug);
    }

    protected Instant ago(long minutes) {
        return clock.instant().minusSeconds(minutes * 60);
    }
}
