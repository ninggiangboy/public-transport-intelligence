package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.transit.domain.BucketKey;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.EtaRow;
import dev.pti.api.transit.domain.GeometrySource;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.apitest.TransitData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** E-03 {@code GET /routes/{routeId}/delays} and E-04 {@code GET /routes/{routeId}/delay-profile} (DOC-32 §3). */
class RouteDelaysControllerTest extends TransitWebTest {

    private static final Instant TRIP_UPDATE_AT = Instant.parse("2026-09-29T21:19:30Z");
    private static final Instant ETA_AT = Instant.parse("2026-09-29T21:05:12Z");

    @BeforeEach
    void route() {
        transit.routes.add(TransitData.route("18", 3, 18));
        PatternStop first = new PatternStop("51405", "51405", "Nicollet Ave & 46th St", 44.92, -93.27, 1);
        PatternStop second = new PatternStop("51406", "51406", "Nicollet Ave & 44th St", 44.93, -93.27, 2);
        transit.details.put(
                "18",
                new RouteDetail(
                        3,
                        transit.routes.get(0),
                        List.of(new DirectionPattern(
                                0,
                                "NB",
                                "Downtown",
                                312,
                                "s",
                                GeometrySource.SHAPE,
                                List.of(),
                                List.of(first, second)))));
        probe(null, TRIP_UPDATE_AT, ETA_AT);
    }

    private static DelayBucket bucket(BucketKey key) {
        return new DelayBucket(key, new BigDecimal("142.6"), 118, 391, 1204, new BigDecimal("81.23"));
    }

    private MockHttpServletRequestBuilder delays() {
        return get("/api/v1/routes/18/delays").header("Authorization", viewer());
    }

    // ------------------------------------------------------------------------------------------------ E-03

    @Test
    @DisplayName("E-03 is for a viewer: anonymous is a 401, and a viewer and an operator are let in")
    void needsAViewer() throws Exception {
        mvc.perform(get("/api/v1/routes/18/delays")).andExpect(status().isUnauthorized());
        mvc.perform(delays()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/routes/18/delays").header("Authorization", operator()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E-03 hourly buckets carry bucketStart; the numbers keep the scale of the database")
    void hourlyShape() throws Exception {
        transit.delayBuckets.add(bucket(new BucketKey.Hourly(Instant.parse("2026-09-29T20:00:00Z"))));

        mvc.perform(delays())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, private"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-29T21:19:30Z"))
                .andExpect(jsonPath("$.routeId").value("18"))
                .andExpect(jsonPath("$.bucket").value("hour"))
                .andExpect(jsonPath("$.earlyToleranceSeconds").value(300))
                .andExpect(jsonPath("$.lateToleranceSeconds").value(300))
                .andExpect(jsonPath("$.from").isString())
                .andExpect(jsonPath("$.to").isString())
                .andExpect(jsonPath("$.items[0].bucketStart").value("2026-09-29T20:00:00Z"))
                .andExpect(jsonPath("$.items[0].serviceDate").doesNotExist())
                .andExpect(jsonPath("$.items[0].dayOfWeek").doesNotExist())
                .andExpect(jsonPath("$.items[0].avgDelaySeconds").value(142.6))
                .andExpect(jsonPath("$.items[0].medianDelaySeconds").value(118))
                .andExpect(jsonPath("$.items[0].p90DelaySeconds").value(391))
                .andExpect(jsonPath("$.items[0].observationCount").value(1204))
                .andExpect(jsonPath("$.items[0].onTimePercentage").value(81.23));
    }

    @Test
    @DisplayName("E-03 daily buckets carry serviceDate, hour-of-week buckets carry dayOfWeek and hourOfDay")
    void otherBucketShapes() throws Exception {
        transit.delayBuckets.add(bucket(new BucketKey.Daily(LocalDate.parse("2026-09-29"))));
        mvc.perform(delays().param("bucket", "day"))
                .andExpect(jsonPath("$.bucket").value("day"))
                .andExpect(jsonPath("$.items[0].serviceDate").value("2026-09-29"))
                .andExpect(jsonPath("$.items[0].bucketStart").doesNotExist());
        assertThat(transit.lastDelayRequest.bucket()).isEqualTo(BucketSize.DAY);

        transit.delayBuckets.clear();
        transit.delayBuckets.add(bucket(new BucketKey.WeekHour(2, 16)));
        mvc.perform(delays().param("bucket", "hour-of-week"))
                .andExpect(jsonPath("$.bucket").value("hour-of-week"))
                .andExpect(jsonPath("$.items[0].dayOfWeek").value(2))
                .andExpect(jsonPath("$.items[0].hourOfDay").value(16))
                .andExpect(jsonPath("$.items[0].serviceDate").doesNotExist());
        assertThat(transit.lastDelayRequest.bucket()).isEqualTo(BucketSize.HOUR_OF_WEEK);
    }

    @Test
    @DisplayName("E-03 the default range is the 7 days up to business now; the direction reaches the query")
    void defaultRange() throws Exception {
        mvc.perform(delays().param("directionId", "1")).andExpect(status().isOk());

        assertThat(transit.lastDelayRequest.directionId()).isEqualTo(1);
        assertThat(transit.lastDelayRequest.bucket()).isEqualTo(BucketSize.HOUR);
        Instant to = transit.lastDelayRequest.to();
        assertThat(Duration.between(to, clock.instant())).isBetween(Duration.ZERO, Duration.ofSeconds(61));
        assertThat(Duration.between(transit.lastDelayRequest.from(), to)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("AG-06 from=-60m is business now minus 60 minutes")
    void relativeFrom() throws Exception {
        mvc.perform(delays().param("from", "-60m")).andExpect(status().isOk());

        Duration age = Duration.between(transit.lastDelayRequest.from(), clock.instant());
        assertThat(age).isBetween(Duration.ofMinutes(60), Duration.ofSeconds(3605));
    }

    @Test
    @DisplayName("E-03 an explicit range with offsets is taken as it is")
    void explicitRange() throws Exception {
        mvc.perform(delays().param("from", "2026-09-22T16:00:00-05:00").param("to", "2026-09-29T16:00:00-05:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-22T21:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-09-29T21:00:00Z"));
    }

    @Test
    @DisplayName("AG-05 a time without an offset is a 400 on the field")
    void timeNeedsAnOffset() throws Exception {
        mvc.perform(delays().param("from", "2026-09-29T10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    @DisplayName("AG-07 a range of 32 days is a 400")
    void rangeTooLong() throws Exception {
        mvc.perform(delays().param("from", "-32d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc.perform(delays().param("from", "-31d")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("E-03 from not before to, and a to more than a day ahead, are 400s")
    void rangeOrder() throws Exception {
        mvc.perform(delays().param("from", "-1h").param("to", "-2h")).andExpect(status().isBadRequest());
        mvc.perform(delays().param("from", "-1h").param("to", "2099-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E-03 a bucket that is not hour, day or hour-of-week, and a direction that is not 0 or 1, are 400s")
    void parameterValidation() throws Exception {
        mvc.perform(delays().param("bucket", "week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bucket"));
        mvc.perform(delays().param("directionId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("directionId"));
        mvc.perform(delays().param("bucket", "HOUR")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E-03 a route that is not in the feed is a 404, after the parameters are checked")
    void unknownRoute() throws Exception {
        mvc.perform(get("/api/v1/routes/nope/delays").header("Authorization", viewer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/routes/nope/delays").param("bucket", "week").header("Authorization", viewer()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------------ E-04

    private static EtaRow eta(String stopId, int samples) {
        return new EtaRow(
                stopId,
                new BigDecimal("64.2"),
                51,
                170,
                samples,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-28"),
                ETA_AT);
    }

    @Test
    @DisplayName("E-04 is open to everyone; a stop with history has figures, one without has only NONE")
    void profileShape() throws Exception {
        transit.etaRows.add(eta("51405", 36));

        mvc.perform(get("/api/v1/routes/18/delay-profile")
                        .param("directionId", "0")
                        .param("dayOfWeek", "2")
                        .param("hourOfDay", "16"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(header().string("X-Data-As-Of", "2026-09-29T21:05:12Z"))
                .andExpect(jsonPath("$.routeId").value("18"))
                .andExpect(jsonPath("$.directionId").value(0))
                .andExpect(jsonPath("$.dayOfWeek").value(2))
                .andExpect(jsonPath("$.hourOfDay").value(16))
                .andExpect(jsonPath("$.windowStart").value("2026-09-01"))
                .andExpect(jsonPath("$.windowEnd").value("2026-09-28"))
                .andExpect(jsonPath("$.computedAt").value("2026-09-29T21:05:12Z"))
                .andExpect(jsonPath("$.items[0].stopId").value("51405"))
                .andExpect(jsonPath("$.items[0].name").value("Nicollet Ave & 46th St"))
                .andExpect(jsonPath("$.items[0].stopSequence").value(1))
                .andExpect(jsonPath("$.items[0].avgDelaySeconds").value(64.2))
                .andExpect(jsonPath("$.items[0].medianDelaySeconds").value(51))
                .andExpect(jsonPath("$.items[0].p90DelaySeconds").value(170))
                .andExpect(jsonPath("$.items[0].sampleCount").value(36))
                .andExpect(jsonPath("$.items[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.items[1].stopId").value("51406"))
                .andExpect(jsonPath("$.items[1].sampleCount").value(0))
                .andExpect(jsonPath("$.items[1].confidence").value("NONE"))
                .andExpect(jsonPath("$.items[1].avgDelaySeconds").doesNotExist())
                .andExpect(jsonPath("$.items[1].medianDelaySeconds").doesNotExist())
                .andExpect(jsonPath("$.items[1].p90DelaySeconds").doesNotExist());
    }

    @Test
    @DisplayName("E-04 without any history the window and computedAt are absent")
    void profileWithoutHistory() throws Exception {
        mvc.perform(get("/api/v1/routes/18/delay-profile").param("directionId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowStart").doesNotExist())
                .andExpect(jsonPath("$.computedAt").doesNotExist())
                .andExpect(jsonPath("$.dayOfWeek").isNumber())
                .andExpect(jsonPath("$.hourOfDay").isNumber());
    }

    @Test
    @DisplayName("E-04 directionId is required and must be 0 or 1; dayOfWeek is 1-7 and hourOfDay 0-23")
    void profileValidation() throws Exception {
        mvc.perform(get("/api/v1/routes/18/delay-profile")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/routes/18/delay-profile").param("directionId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("directionId"));
        mvc.perform(get("/api/v1/routes/18/delay-profile")
                        .param("directionId", "0")
                        .param("dayOfWeek", "8")
                        .param("hourOfDay", "24"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    @DisplayName("E-04 a route or a direction that does not exist is a 404")
    void profileNotFound() throws Exception {
        mvc.perform(get("/api/v1/routes/nope/delay-profile").param("directionId", "0"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/routes/18/delay-profile").param("directionId", "1"))
                .andExpect(status().isNotFound());
    }
}
