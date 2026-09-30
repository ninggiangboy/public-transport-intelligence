package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.transit.domain.LiveVehicle;
import dev.pti.api.transit.domain.OpenBunching;
import dev.pti.apitest.TransitData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E-05 {@code GET /vehicles/live} (DOC-32 §3), through HTTP. */
class VehicleControllerTest extends TransitWebTest {

    private static final UUID EPISODE = UUID.fromString("6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c");

    private Instant now;

    @BeforeEach
    void vehicles() {
        now = clock.instant();
        transit.vehicles.add(TransitData.vehicle("1203", "18", now.minusSeconds(5)));
        transit.vehicles.add(TransitData.vehicle("1187", "18", now.minusSeconds(20)));
        transit.vehicles.add(TransitData.vehicle("3000", "901", now.minusSeconds(2)));
        probe(now.minusSeconds(3), null, null);
    }

    @Test
    @DisplayName("E-05 the snapshot: count, businessNow and the members of each vehicle, ordered by vehicle id")
    void shape() throws Exception {
        LiveVehicle bare = new LiveVehicle(
                "0001", null, "18", "t", 1, null, 44.9, -93.2, null, null, "STOPPED_AT", "s", 1, null, now, null, null);
        transit.vehicles.add(bare);

        mvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Vary", "Authorization"))
                .andExpect(header().exists("X-Data-As-Of"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.businessNow").isString())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].vehicleId").value("0001"))
                .andExpect(jsonPath("$.items[0].label").doesNotExist())
                .andExpect(jsonPath("$.items[0].headsign").doesNotExist())
                .andExpect(jsonPath("$.items[0].bearing").doesNotExist())
                .andExpect(jsonPath("$.items[0].occupancyStatus").doesNotExist())
                .andExpect(jsonPath("$.items[0].delaySeconds").doesNotExist())
                .andExpect(jsonPath("$.items[0].stopArrivalAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].vehicleId").value("1187"))
                .andExpect(jsonPath("$.items[2].vehicleId").value("1203"))
                .andExpect(jsonPath("$.items[2].label").value("1203"))
                .andExpect(jsonPath("$.items[2].routeId").value("18"))
                .andExpect(jsonPath("$.items[2].tripId").value("trip-1203"))
                .andExpect(jsonPath("$.items[2].directionId").value(0))
                .andExpect(jsonPath("$.items[2].headsign").value("Downtown"))
                .andExpect(jsonPath("$.items[2].lat").value(44.948121))
                .andExpect(jsonPath("$.items[2].lon").value(-93.278004))
                .andExpect(jsonPath("$.items[2].bearing").value(358.0))
                .andExpect(jsonPath("$.items[2].speedMps").value(7.4))
                .andExpect(jsonPath("$.items[2].currentStatus").value("IN_TRANSIT_TO"))
                .andExpect(jsonPath("$.items[2].stopId").value("51420"))
                .andExpect(jsonPath("$.items[2].currentStopSequence").value(14))
                .andExpect(jsonPath("$.items[2].occupancyStatus").value("MANY_SEATS_AVAILABLE"))
                .andExpect(jsonPath("$.items[2].eventTimestamp").isString())
                .andExpect(jsonPath("$.items[2].delaySeconds").value(95))
                .andExpect(jsonPath("$.items[2].stopArrivalAt").isString())
                .andExpect(jsonPath("$.items[2].bunching").doesNotExist());
    }

    @Test
    @DisplayName("E-05 X-Data-As-Of is the last vehicle position; it is left out when there has never been one")
    void dataAsOf() throws Exception {
        mvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(header().string(
                                "X-Data-As-Of",
                                now.minusSeconds(3)
                                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                        .toString()));

        probe(null, null, null);
        assertThat(mvc.perform(get("/api/v1/vehicles/live"))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Data-As-Of"))
                .isNull();
    }

    @Test
    @DisplayName("EP-05 a vehicle that has not reported for over 5 minutes is not in the snapshot")
    void oldVehiclesAreLeftOut() throws Exception {
        transit.vehicles.add(TransitData.vehicle("9999", "18", now.minusSeconds(301)));

        mvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.items[?(@.vehicleId == '9999')]").isEmpty());
    }

    @Test
    @DisplayName("E-05 routeId filters; repeated or comma-separated, at most 20 of them")
    void routeFilter() throws Exception {
        mvc.perform(get("/api/v1/vehicles/live").param("routeId", "901"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].vehicleId").value("3000"));
        mvc.perform(get("/api/v1/vehicles/live").param("routeId", "901", "18"))
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/v1/vehicles/live").param("routeId", "901,18"))
                .andExpect(jsonPath("$.count").value(3));
        assertThat(transit.lastVehicleRoutes).containsExactlyInAnyOrder("901", "18");
        mvc.perform(get("/api/v1/vehicles/live").param("routeId", "nope"))
                .andExpect(jsonPath("$.count").value(0));

        String many = String.join(
                ",",
                java.util.stream.IntStream.rangeClosed(1, 21)
                        .mapToObj(Integer::toString)
                        .toList());
        mvc.perform(get("/api/v1/vehicles/live").param("routeId", many))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("routeId"));
    }

    @Test
    @DisplayName("AG-08 an unknown parameter is a 400 naming it")
    void unknownParameter() throws Exception {
        mvc.perform(get("/api/v1/vehicles/live").param("routeID", "18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("routeID"));
    }

    @Test
    @DisplayName("EP-06 anonymous never gets bunching; a viewer gets it on the leader and on the follower")
    void bunchingOverlay() throws Exception {
        transit.bunching.add(new OpenBunching(EPISODE, "1187", "1203", 112, 600));

        mvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(jsonPath("$.items[0].bunching").doesNotExist())
                .andExpect(jsonPath("$.items[1].bunching").doesNotExist());

        mvc.perform(get("/api/v1/vehicles/live").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].vehicleId").value("1187"))
                .andExpect(jsonPath("$.items[0].bunching.episodeId").value(EPISODE.toString()))
                .andExpect(jsonPath("$.items[0].bunching.role").value("LEADER"))
                .andExpect(jsonPath("$.items[0].bunching.partnerVehicleId").value("1203"))
                .andExpect(jsonPath("$.items[0].bunching.gapSeconds").value(112))
                .andExpect(jsonPath("$.items[0].bunching.headwaySeconds").value(600))
                .andExpect(jsonPath("$.items[1].vehicleId").value("1203"))
                .andExpect(jsonPath("$.items[1].bunching.role").value("FOLLOWER"))
                .andExpect(jsonPath("$.items[1].bunching.partnerVehicleId").value("1187"))
                .andExpect(jsonPath("$.items[2].bunching").doesNotExist());
    }

    @Test
    @DisplayName("A bad token on the public snapshot is a 401, not a silent downgrade to anonymous")
    void badTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/vehicles/live").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AG-19 without an ACTIVE feed the snapshot is a 503")
    void noActiveFeed() throws Exception {
        transit.feed = null;

        mvc.perform(get("/api/v1/vehicles/live"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"));
    }
}
