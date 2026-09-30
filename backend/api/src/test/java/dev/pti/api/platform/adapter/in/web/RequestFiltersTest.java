package dev.pti.api.platform.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ProblemType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import tools.jackson.databind.json.JsonMapper;

class RequestFiltersTest {

    private final ProblemWriter problems = new ProblemWriter(
            new ProblemFactory(new SimpleMeterRegistry()), JsonMapper.builder().build());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("FR-10.6 without tracing the filter makes a 32-hex trace id, puts it in the MDC and removes it after")
    void generatesATraceId() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestFilter(problems)
                .doFilter(
                        new MockHttpServletRequest("GET", "/api/v1/me"),
                        response,
                        (req, res) -> seenInChain.set(MDC.get(ApiRequestFilter.TRACE_ID_KEY)));

        assertThat(response.getHeader("X-Trace-Id")).matches("[0-9a-f]{32}").isEqualTo(seenInChain.get());
        assertThat(MDC.get(ApiRequestFilter.TRACE_ID_KEY)).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("With tracing on, the trace id of the request is the one that goes in the header")
    void usesTheTraceIdOfTheTracer() throws Exception {
        // The W3C Trace Context sample id; a local variable keeps secret scanners from reading it as a key value.
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MDC.put(ApiRequestFilter.TRACE_ID_KEY, traceId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestFilter(problems)
                .doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, (req, res) -> {});

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(traceId);
        assertThat(MDC.get(ApiRequestFilter.TRACE_ID_KEY)).isEqualTo(traceId);
    }

    @Test
    @DisplayName("Keycloak unreachable while a token is checked leaves the security chain as 503 with Retry-After")
    void authenticationServiceFailureIs503() throws Exception {
        FilterChain failing = (req, res) -> {
            throw new AuthenticationServiceException("could not reach http://keycloak:8080");
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestFilter(problems).doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, failing);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("urn:pti:problem:service-unavailable", "traceId")
                .doesNotContain("keycloak");
    }

    @Test
    @DisplayName("Any other exception that escapes the chain is a bare 500 Problem")
    void unexpectedFailureIs500() throws Exception {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("secret detail");
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestFilter(problems).doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, failing);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .contains("urn:pti:problem:internal-error")
                .doesNotContain("secret detail", "IllegalStateException");
    }

    // ------------------------------------------------------------------------------------ body size limit

    @Test
    @DisplayName("A body that announces more than the limit is 413 and never reaches the chain")
    void announcedSizeIsChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/etl/replays");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        new BodySizeLimitFilter(10, problems).doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("urn:pti:problem:payload-too-large");
        assertThat(reached.get()).isFalse();
    }

    @Test
    @DisplayName("A body within the limit passes, at the limit exactly")
    void withinTheLimitPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/etl/replays");
        request.setContent(new byte[10]);
        AtomicReference<Integer> read = new AtomicReference<>();

        new BodySizeLimitFilter(10, problems)
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> read.set(req.getInputStream().readAllBytes().length));

        assertThat(read.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("A chunked body that turns out longer than the limit fails while it is read")
    void chunkedBodyIsCountedWhileRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/etl/replays") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(new ByteArrayInputStream(new byte[50]));
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };

        assertThatThrownBy(() -> new BodySizeLimitFilter(10, problems)
                        .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                            try {
                                req.getInputStream().readAllBytes();
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                        }))
                .isInstanceOf(PayloadTooLargeException.class)
                .satisfies(e ->
                        assertThat(((PayloadTooLargeException) e).type()).isEqualTo(ProblemType.PAYLOAD_TOO_LARGE));
    }
}
