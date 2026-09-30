package dev.pti.api.platform.adapter.in.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter.Bucket;
import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter.Decision;
import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter.Limits;
import dev.pti.api.platform.adapter.in.web.ProblemFactory;
import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class RateLimiterTest {

    private final AtomicLong millis = new AtomicLong();
    private final TimeMeter clock = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return millis.get() * 1_000_000;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimiter limiter = new RateLimiter(new Limits(60, 600, 30), registry, clock);

    @Test
    @DisplayName("AG-12 the 61st anonymous request in a minute is refused, with a wait of at least a second")
    void publicBucketHoldsSixtyPerMinute() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryConsume(Bucket.PUBLIC, "203.0.113.5").allowed())
                    .isTrue();
        }

        Decision denied = limiter.tryConsume(Bucket.PUBLIC, "203.0.113.5");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).isEqualTo(60);
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 2L);
        assertThat(registry.get("pti.api.rate.limited")
                        .tag("bucket", "public")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void bucketsAreKeptPerKeyAndPerKind() {
        for (int i = 0; i < 60; i++) {
            limiter.tryConsume(Bucket.PUBLIC, "a");
        }

        assertThat(limiter.tryConsume(Bucket.PUBLIC, "a").allowed()).isFalse();
        assertThat(limiter.tryConsume(Bucket.PUBLIC, "b").allowed()).isTrue();
        assertThat(limiter.tryConsume(Bucket.AUTHENTICATED, "a").allowed()).isTrue();
    }

    @Test
    @DisplayName("Tokens come back evenly: one per second at 60 a minute")
    void refillsGreedily() {
        for (int i = 0; i < 60; i++) {
            limiter.tryConsume(Bucket.PUBLIC, "a");
        }
        assertThat(limiter.tryConsume(Bucket.PUBLIC, "a").allowed()).isFalse();

        millis.addAndGet(Duration.ofSeconds(1).toMillis());

        assertThat(limiter.tryConsume(Bucket.PUBLIC, "a").allowed()).isTrue();
        assertThat(limiter.tryConsume(Bucket.PUBLIC, "a").allowed()).isFalse();
    }

    @Test
    void remainingCountsDown() {
        assertThat(limiter.tryConsume(Bucket.WRITE, "a").remaining()).isEqualTo(29);
        assertThat(limiter.tryConsume(Bucket.WRITE, "a").remaining()).isEqualTo(28);
    }

    // ---------------------------------------------------------------------------------------------- filter

    private RateLimitFilter filter(Limits limits) {
        ProblemFactory problems = new ProblemFactory(registry);
        return new RateLimitFilter(
                new RateLimiter(limits, registry, clock),
                new ProblemWriter(problems, JsonMapper.builder().build()));
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest from(String ip, String method) {
        MockHttpServletRequest request = method.equals("GET")
                ? new MockHttpServletRequest("GET", "/api/v1/routes")
                : new MockHttpServletRequest("POST", "/api/v1/etl/replays");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("The filter answers 429 Problem with Retry-After, retryAfterSeconds and the X-RateLimit headers")
    void filterRefusesWithAProblem() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitFilter filter = filter(new Limits(2, 600, 30));

        MockHttpServletResponse first = call(filter, from("10.0.0.1", "GET"));
        call(filter, from("10.0.0.1", "GET"));
        MockHttpServletResponse third = call(filter, from("10.0.0.1", "GET"));
        MockHttpServletResponse otherClient = call(filter, from("10.0.0.2", "GET"));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentType()).startsWith("application/problem+json");
        assertThat(Long.parseLong(third.getHeader("Retry-After"))).isPositive();
        assertThat(third.getContentAsString())
                .contains("\"type\":\"urn:pti:problem:rate-limited\"")
                .contains("\"retryAfterSeconds\":");
        assertThat(otherClient.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("A caller with a token draws on the authenticated bucket of its sub, whatever its address")
    void authenticatedCallersAreKeyedBySubject() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("preferred_username", "viewer")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
        try {
            RateLimitFilter filter = filter(new Limits(1, 3, 30));

            for (int i = 0; i < 3; i++) {
                assertThat(call(filter, from("10.0." + i + ".1", "GET")).getStatus())
                        .isEqualTo(200);
            }
            assertThat(call(filter, from("10.9.9.9", "GET")).getStatus()).isEqualTo(429);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("POST also draws on the write bucket: 30 a minute")
    void writesAreLimitedFurther() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitFilter filter = filter(new Limits(600, 600, 2));

        assertThat(call(filter, from("10.0.0.1", "POST")).getStatus()).isEqualTo(200);
        assertThat(call(filter, from("10.0.0.1", "POST")).getStatus()).isEqualTo(200);
        MockHttpServletResponse denied = call(filter, from("10.0.0.1", "POST"));

        assertThat(denied.getStatus()).isEqualTo(429);
        assertThat(denied.getHeader("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(call(filter, from("10.0.0.1", "GET")).getStatus()).isEqualTo(200);
    }
}
