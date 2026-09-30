package dev.pti.api.system.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

/** E-60 {@code GET /system/freshness} and E-61 {@code GET /me} (DOC-32 §10, EP-33, EP-34). */
@ResourceLock("freshness-probe-result")
class SystemEndpointsTest extends ApiWebTestSupport {

    @Autowired
    private FreshnessSnapshots snapshots;

    @Autowired
    private ApiCaches caches;

    @Autowired
    private BusinessClock clock;

    @BeforeEach
    void emptyProbe() {
        caches.cache("freshness").invalidateAll();
    }

    private void probeFound(long vehicleAge, long tripUpdateAge, long salesAge, boolean probeError, long probeAge) {
        Instant now = clock.instant();
        ActiveFeed feed = new ActiveFeed(
                3,
                "2026-08-23",
                ZoneId.of("America/Chicago"),
                LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-12-12"),
                Instant.parse("2026-09-27T08:34:40Z"));
        snapshots.store(new FreshnessSnapshot(
                now.minusSeconds(probeAge),
                feed,
                Map.of(
                        SourceKind.GTFS_RT_VEHICLE_POSITION, now.minusSeconds(vehicleAge),
                        SourceKind.GTFS_RT_TRIP_UPDATE, now.minusSeconds(tripUpdateAge),
                        SourceKind.TICKETING_SALES, now.minusSeconds(salesAge)),
                Instant.parse("2026-09-29T21:05:12Z"),
                now,
                Instant.parse("2026-09-29T08:00:41.123456Z"),
                probeError));
    }

    // ---------------------------------------------------------------------------------------------- E-61

    @Test
    @DisplayName("EP-34 without a token: authenticated false and no roles, not a 401")
    void meAnonymous() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.tokenExpiresAt").doesNotExist());
    }

    @Test
    void meViewer() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", JwtFixture.bearer(JwtFixture.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("viewer"))
                .andExpect(jsonPath("$.displayName").value("Demo Viewer"))
                .andExpect(jsonPath("$.roles[0]").value("viewer"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.tokenExpiresAt").isString());
    }

    @Test
    @DisplayName("EP-34 an operator's roles are the effective ones, sorted by name")
    void meOperator() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", JwtFixture.bearer(JwtFixture.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator"))
                .andExpect(jsonPath("$.roles[0]").value("operator"))
                .andExpect(jsonPath("$.roles[1]").value("viewer"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("An operator token that carries only the operator role still yields viewer through the hierarchy")
    void meOperatorOnlyRole() throws Exception {
        String token = JwtFixture.token().username("op").roles("operator").build();

        mvc.perform(get("/api/v1/me").header("Authorization", JwtFixture.bearer(token)))
                .andExpect(jsonPath("$.roles[0]").value("operator"))
                .andExpect(jsonPath("$.roles[1]").value("viewer"));
    }

    @Test
    void meWithABadTokenIsA401() throws Exception {
        mvc.perform(get("/api/v1/me")
                        .header(
                                "Authorization",
                                JwtFixture.bearer(JwtFixture.token().expired().build())))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------------- E-60

    @Test
    @DisplayName("E-60 answers from the last probe, with the active feed and X-Data-As-Of = checkedAt")
    void freshnessFromTheProbe() throws Exception {
        probeFound(5, 33, 44, false, 5);

        mvc.perform(get("/api/v1/system/freshness"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().exists("X-Data-As-Of"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.clockOffset").value("PT0S"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.probeError").doesNotExist())
                .andExpect(jsonPath("$.activeFeed.feedVersionId").value(3))
                .andExpect(jsonPath("$.activeFeed.timezone").value("America/Chicago"))
                .andExpect(jsonPath("$.activeFeed.validFrom").value("2026-08-23"))
                .andExpect(jsonPath("$.activeFeed.validTo").value("2026-12-12"))
                .andExpect(jsonPath("$.activeFeed.activatedAt").value("2026-09-27T08:34:40Z"))
                .andExpect(jsonPath("$.sources.length()").value(3))
                .andExpect(jsonPath("$.sources[0].source").value("GTFS_RT_VEHICLE_POSITION"))
                .andExpect(jsonPath("$.sources[0].ageSeconds").isNumber())
                .andExpect(jsonPath("$.sources[0].staleAfterSeconds").value(120))
                .andExpect(jsonPath("$.sources[0].stale").value(false))
                .andExpect(jsonPath("$.sources[2].source").value("TICKETING_SALES"))
                .andExpect(jsonPath("$.sources[2].staleAfterSeconds").value(900))
                .andExpect(jsonPath("$.insights.etaComputedAt").value("2026-09-29T21:05:12Z"))
                .andExpect(jsonPath("$.insights.otpComputedAt").value("2026-09-29T08:00:41.123Z"));
    }

    @Test
    @DisplayName("EP-33 stale GTFS-realtime: stale sources and the root flag")
    void staleFlag() throws Exception {
        probeFound(200, 10, 10, false, 5);

        mvc.perform(get("/api/v1/system/freshness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.sources[0].stale").value(true))
                .andExpect(jsonPath("$.sources[1].stale").value(false));
    }

    @Test
    @DisplayName("probeError: true appears only when the latest probe failed")
    void probeErrorIsShown() throws Exception {
        probeFound(5, 5, 5, true, 20);

        mvc.perform(get("/api/v1/system/freshness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probeError").value(true));
    }

    @Test
    @DisplayName("A probe result older than 60 s is a 503 with Retry-After")
    void oldProbeIs503() throws Exception {
        probeFound(5, 5, 5, true, 61);

        mvc.perform(get("/api/v1/system/freshness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:service-unavailable"));
    }

    @Test
    void noProbeYetIs503() throws Exception {
        mvc.perform(get("/api/v1/system/freshness")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("Without an ACTIVE feed the endpoint still answers 200, with no activeFeed")
    void noActiveFeed() throws Exception {
        Instant now = clock.instant();
        snapshots.store(new FreshnessSnapshot(now, null, Map.of(), null, now, null, false));

        mvc.perform(get("/api/v1/system/freshness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeFeed").doesNotExist())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.sources[0].lastEventAt").doesNotExist())
                .andExpect(jsonPath("$.sources[0].ageSeconds").doesNotExist())
                .andExpect(jsonPath("$.insights.etaComputedAt").doesNotExist());
    }

    @Test
    void freshnessIsOpenToEveryone() throws Exception {
        probeFound(5, 5, 5, false, 5);

        for (String authorization : new String[] {null, JwtFixture.bearer(JwtFixture.viewer())}) {
            var request = get("/api/v1/system/freshness");
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
        }
    }
}
