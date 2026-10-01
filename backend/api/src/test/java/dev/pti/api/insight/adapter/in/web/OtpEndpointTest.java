package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.insight.domain.OtpDay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** E-14 {@code GET /insights/otp} (DOC-32 §4, EP-13). */
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class OtpEndpointTest extends InsightWebSupport {

    private LocalDate yesterday() {
        return clock.instant()
                .atZone(ZoneId.of("America/Chicago"))
                .toLocalDate()
                .minusDays(1);
    }

    private void day(String route, LocalDate date, int onTime, int early, int late, int earlyTol) {
        int observations = onTime + early + late;
        insight.otpDays.add(new OtpDay(
                route,
                date,
                BigDecimal.valueOf(onTime * 10000 / observations, 2),
                onTime,
                early,
                late,
                observations,
                20,
                earlyTol,
                300));
    }

    private MockHttpServletResponse call(String query) throws Exception {
        return mvc.perform(as(get("/api/v1/insights/otp" + query), viewer()))
                .andReturn()
                .getResponse();
    }

    @Test
    @DisplayName("By default: the seven days that end yesterday, worst route first, with the daily series")
    void defaults() throws Exception {
        day("18", yesterday(), 80, 5, 15, 300);
        day("18", yesterday().minusDays(1), 60, 10, 30, 300);
        day("901", yesterday(), 95, 2, 3, 300);
        day("18", yesterday().minusDays(10), 1, 0, 99, 300);

        MockHttpServletResponse response = call("");
        JsonNode body = okBody(response);

        assertThat(body.path("toDate").asString()).isEqualTo(yesterday().toString());
        assertThat(body.path("fromDate").asString())
                .isEqualTo(yesterday().minusDays(6).toString());
        assertThat(body.path("items"))
                .extracting(item -> item.path("routeId").asString())
                .containsExactly("18", "901");
        JsonNode route18 = body.path("items").get(0);
        assertThat(route18.path("onTimeCount").asInt()).isEqualTo(140);
        assertThat(route18.path("observationCount").asInt()).isEqualTo(200);
        assertThat(route18.path("otpPercentage").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(route18.path("earlyToleranceSeconds").asInt()).isEqualTo(300);
        assertThat(route18.has("mixedTolerances")).isFalse();
        assertThat(route18.path("daily")).hasSize(2);
        assertThat(route18.path("daily").get(0).path("serviceDate").asString())
                .isEqualTo(yesterday().minusDays(1).toString());
        assertThat(response.getHeader("X-Data-As-Of")).isEqualTo(OTP_AT.toString());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("max-age=60, private");
    }

    @Test
    @DisplayName("EP-13 two days with other tolerances: mixedTolerances and no tolerance, the percentage from the sums")
    void mixedTolerances() throws Exception {
        day("18", yesterday(), 90, 5, 5, 300);
        day("18", yesterday().minusDays(1), 10, 45, 45, 240);

        JsonNode route = okBody(call("")).path("items").get(0);

        assertThat(route.path("mixedTolerances").asBoolean()).isTrue();
        assertThat(route.has("earlyToleranceSeconds")).isFalse();
        assertThat(route.has("lateToleranceSeconds")).isFalse();
        assertThat(route.path("otpPercentage").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("fromDate, toDate, routeId and routeType narrow the answer")
    void parameters() throws Exception {
        day("18", LocalDate.parse("2026-09-01"), 90, 5, 5, 300);
        day("20", LocalDate.parse("2026-09-01"), 90, 5, 5, 300);
        day("18", LocalDate.parse("2026-09-09"), 90, 5, 5, 300);

        assertThat(okBody(call("?fromDate=2026-09-01&toDate=2026-09-05")).path("items"))
                .hasSize(2);
        assertThat(okBody(call("?fromDate=2026-09-01&toDate=2026-09-05&routeId=18"))
                        .path("items"))
                .hasSize(1);
        assertThat(okBody(call("?fromDate=2026-09-01&toDate=2026-09-30&routeId=18,20"))
                        .path("items"))
                .hasSize(2);
        okBody(call("?fromDate=2026-09-01&toDate=2026-09-05&routeType=3&routeType=0"));
        assertThat(insight.otpRouteTypesAsked).last().isEqualTo(java.util.List.of(0, 3));
    }

    @Test
    @DisplayName("A reversed range, one over 31 days, a bad date, a bad route type, an unknown parameter: 400")
    void badParameters() throws Exception {
        assertValidationError(call("?fromDate=2026-09-05&toDate=2026-09-01"), "fromDate");
        assertValidationError(call("?fromDate=2026-07-01&toDate=2026-09-01"), "fromDate");
        assertValidationError(call("?fromDate=yesterday"), "fromDate");
        assertValidationError(call("?toDate=2026-13-01"), "toDate");
        assertValidationError(call("?routeType=bus"), "routeType");
        assertValidationError(call("?from=2026-09-01"), "from");
    }

    @Test
    @DisplayName("It needs a viewer")
    void needsAViewer() throws Exception {
        assertThat(mvc.perform(get("/api/v1/insights/otp"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
