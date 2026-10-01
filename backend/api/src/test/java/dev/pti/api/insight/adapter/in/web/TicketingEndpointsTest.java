package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.insight.domain.InsightFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** E-15 {@code GET /insights/ticketing-anomalies} and E-16 {@code GET /insights/ticketing-anomalies/{id}} (DOC-32 §4). */
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class TicketingEndpointsTest extends InsightWebSupport {

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    @BeforeEach
    void data() {
        insight.anomalies.add(InsightFixtures.anomaly(id(1), "SP-0142", ago(10), "fraud_suspect", 2));
        insight.anomalies.add(InsightFixtures.anomaly(id(2), "SP-0150", ago(20), "promo_spike", 0));
        insight.anomalies.add(InsightFixtures.anomaly(id(3), "SP-0142", ago(30), null, null));
    }

    private MockHttpServletResponse list(String query) throws Exception {
        return mvc.perform(as(get("/api/v1/insights/ticketing-anomalies" + query), viewer()))
                .andReturn()
                .getResponse();
    }

    private List<String> ids(String query) throws Exception {
        return okBody(list(query))
                .path("items")
                .valueStream()
                .map(item -> item.path("id").asString())
                .toList();
    }

    @Test
    @DisplayName("An anomaly has the members of DOC-32, without the summary and batch of the detail")
    void shape() throws Exception {
        MockHttpServletResponse response = list("");
        JsonNode item = okBody(response).path("items").get(0);

        assertThat(item.propertyNames())
                .containsExactly(
                        "id",
                        "salePointId",
                        "salePointName",
                        "routeId",
                        "windowStart",
                        "windowEnd",
                        "detectedAt",
                        "trigger",
                        "txnCount",
                        "refundCount",
                        "refundRatio",
                        "amountSum",
                        "baselineMean",
                        "baselineStddev",
                        "zScore",
                        "enrichmentStatus",
                        "category",
                        "categoryConfidence",
                        "severity",
                        "severityConfidence",
                        "modelVersion");
        assertThat(item.path("refundRatio").decimalValue()).isEqualByComparingTo("0.48");
        assertThat(response.getHeader("X-Data-As-Of")).isEqualTo(SALES_AT.toString());
    }

    @Test
    @DisplayName("An anomaly that is not enriched yet has enrichmentStatus and none of the AI members")
    void notEnriched() throws Exception {
        JsonNode item = okBody(list("?salePointId=SP-0142")).path("items").get(1);

        assertThat(item.path("id").asString()).isEqualTo(id(3).toString());
        assertThat(item.path("enrichmentStatus").asString()).isEqualTo("PENDING");
        assertThat(item.has("category")).isFalse();
        assertThat(item.has("severity")).isFalse();
        assertThat(item.has("modelVersion")).isFalse();
    }

    @Test
    @DisplayName("Filters: sale point, category (unclassified is a value of its own), severity, trigger")
    void filters() throws Exception {
        assertThat(ids("?salePointId=SP-0142")).containsExactly(id(1).toString(), id(3).toString());
        assertThat(ids("?category=fraud_suspect")).containsExactly(id(1).toString());
        assertThat(ids("?category=unclassified")).containsExactly(id(3).toString());
        assertThat(ids("?category=unclassified,promo_spike")).containsExactly(id(2).toString(), id(3).toString());
        assertThat(ids("?severity=2&severity=0")).containsExactly(id(1).toString(), id(2).toString());
        assertThat(ids("?trigger=REFUND_RATIO")).hasSize(3);
        assertThat(ids("?trigger=VOLUME")).isEmpty();
    }

    @Test
    @DisplayName("Bad values are a 400 on their field; the message of a bad category names the valid ones")
    void badParameters() throws Exception {
        assertValidationError(list("?category=fraud"), "category");
        assertThat(body(list("?category=fraud"))
                        .path("errors")
                        .get(0)
                        .path("message")
                        .asString())
                .contains("fraud_suspect", "unclassified");
        assertValidationError(list("?severity=3"), "severity");
        assertValidationError(list("?trigger=SPIKE"), "trigger");
        assertValidationError(list("?sale_point=1"), "sale_point");
        assertValidationError(list("?limit=0"), "limit");
    }

    @Test
    @DisplayName("Paging is by detectedAt and id, newest first, with a cursor that belongs to the filters")
    void paging() throws Exception {
        JsonNode first = okBody(list("?limit=2"));
        assertThat(first.path("items")).hasSize(2);
        String cursor = first.path("nextCursor").asString();

        JsonNode second = okBody(list("?limit=2&cursor=" + cursor));
        assertThat(second.path("items"))
                .singleElement()
                .satisfies(item -> assertThat(item.path("id").asString()).isEqualTo(id(3).toString()));
        assertThat(second.has("nextCursor")).isFalse();
        assertValidationError(list("?limit=2&severity=1&cursor=" + cursor), "cursor");
    }

    @Test
    @DisplayName("E-16 the detail adds the window summary as JSON and the batch")
    void detail() throws Exception {
        MockHttpServletResponse response = mvc.perform(
                        as(get("/api/v1/insights/ticketing-anomalies/" + id(1)), viewer()))
                .andReturn()
                .getResponse();
        JsonNode body = okBody(response);

        assertThat(body.path("summary").path("refundCount").asInt()).isEqualTo(12);
        assertThat(body.path("batchId").asString()).isEqualTo(InsightFixtures.BATCH_ID.toString());
        assertThat(response.getHeader("X-Data-As-Of")).isEqualTo(SALES_AT.toString());
    }

    @Test
    @DisplayName("E-16 an unknown or malformed id is a 404")
    void detailNotFound() throws Exception {
        assertProblem(
                mvc.perform(as(get("/api/v1/insights/ticketing-anomalies/" + UUID.randomUUID()), viewer()))
                        .andReturn()
                        .getResponse(),
                404,
                "not-found");
        assertProblem(
                mvc.perform(as(get("/api/v1/insights/ticketing-anomalies/x"), viewer()))
                        .andReturn()
                        .getResponse(),
                404,
                "not-found");
    }

    @Test
    @DisplayName("Both need a viewer")
    void needsAViewer() throws Exception {
        assertThat(mvc.perform(get("/api/v1/insights/ticketing-anomalies"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
