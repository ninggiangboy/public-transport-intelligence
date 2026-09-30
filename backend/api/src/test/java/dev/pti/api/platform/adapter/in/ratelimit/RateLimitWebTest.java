package dev.pti.api.platform.adapter.in.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Rate limiting inside the whole application (DOC-31 §11, AG-12): the filter sits after authorization, so a caller who
 * is refused with 401 or 403 spends no tokens, and {@code /internal/**} is not limited at all.
 */
@TestPropertySource(
        properties = {
            "pti.api.rate-limit.enabled=true",
            "pti.api.rate-limit.public-per-minute=3",
            "pti.api.rate-limit.authenticated-per-minute=5",
            "pti.api.rate-limit.write-per-minute=2"
        })
class RateLimitWebTest extends ApiWebTestSupport {

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    @DisplayName("AG-12 after the allowance the request is 429 with Retry-After > 0 and the Problem body")
    void publicBucketRefuses() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/stops/stub-list").with(from("198.51.100.1")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)));
        }

        var denied = mvc.perform(get("/api/v1/stops/stub-list").with(from("198.51.100.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:rate-limited"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        assertThat(Long.parseLong(denied.getResponse().getHeader("Retry-After")))
                .isPositive();
        mvc.perform(get("/api/v1/stops/stub-list").with(from("198.51.100.2"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A valid token draws on the authenticated bucket of its sub, not on the address")
    void authenticatedBucket() throws Exception {
        String token =
                JwtFixture.bearer(JwtFixture.token().username("rate-user").build());
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/stops/stub-list")
                            .header("Authorization", token)
                            .with(from("198.51.100." + (10 + i))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"));
        }

        mvc.perform(get("/api/v1/stops/stub-list")
                        .header("Authorization", token)
                        .with(from("198.51.100.99")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("POST also draws on the write bucket")
    void writeBucket() throws Exception {
        String token = JwtFixture.bearer(
                JwtFixture.token().username("rate-operator").roles("operator").build());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\": \"ok\"}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/v1/alerts/stub-ack/ack")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"ok\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "2"));
        mvc.perform(get("/api/v1/stops/stub-list").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DOC-31 §9 a caller who is not allowed is told so before any token is taken")
    void refusedCallersSpendNoTokens() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/insights/bunching/stub-caller").with(from("198.51.100.50")))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/v1/stops/stub-list").with(from("198.51.100.50"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("DOC-31 §11 /internal/** is not rate limited")
    void internalIsNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/internal/alerts/alertmanager")
                            .header("Authorization", "Bearer " + WEBHOOK_TOKEN)
                            .with(from("198.51.100.77")))
                    .andExpect(status().isNoContent());
        }
    }
}
