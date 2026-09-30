package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The outermost filter of the API. Runs before Spring Security, so even a 401 carries what it sets:
 *
 * <ul>
 *   <li>{@code X-Trace-Id} on every response (FR-10.6, DOC-31 §7.2): the trace id of the request, or, when tracing is
 *       off, a random 32-hex id that it also puts in the MDC;
 *   <li>{@code X-Content-Type-Options: nosniff}, and {@code Cache-Control: no-store} as the default that a controller
 *       replaces with its own policy (DOC-31 §10.3);
 *   <li>one {@code INFO} line per request when it ends (DOC-31 §13): method, route template, status, duration, user and
 *       client IP; never the query string, which can hold what the user typed.
 * </ul>
 *
 * <p>It is also the last net for what the security chain lets through, which the controller advice never sees: Keycloak
 * unreachable while a token is checked is a 503, any other exception a bare 500 (DOC-27 §5.4).
 */
public final class ApiRequestFilter extends OncePerRequestFilter {

    /** The MDC key that Micrometer Tracing fills with the trace id. */
    public static final String TRACE_ID_KEY = "traceId";

    /** Request attribute with the caller's name, set by the security chain once the token is read. */
    public static final String USER_ATTRIBUTE = "dev.pti.api.user";

    private static final Logger log = LoggerFactory.getLogger(ApiRequestFilter.class);
    private static final String UNMATCHED = "UNMATCHED";
    private static final String TRANSIENT_RETRY_SECONDS = "5";

    private final ProblemWriter problems;

    public ApiRequestFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get(TRACE_ID_KEY);
        boolean generated = traceId == null || traceId.isBlank();
        if (generated) {
            traceId = randomTraceId();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException e) {
            log.warn("Token could not be checked: {}", e.getClass().getSimpleName());
            failWith(request, response, ProblemType.SERVICE_UNAVAILABLE, e);
        } catch (RuntimeException e) {
            log.error("Unhandled exception outside the controllers", e);
            failWith(request, response, ProblemType.INTERNAL_ERROR, e);
        } finally {
            logCompleted(request, response, (System.nanoTime() - start) / 1_000_000);
            if (generated) {
                MDC.remove(TRACE_ID_KEY);
            }
        }
    }

    private void failWith(
            HttpServletRequest request, HttpServletResponse response, ProblemType type, RuntimeException cause)
            throws IOException {
        if (response.isCommitted()) {
            throw cause;
        }
        response.reset();
        response.setHeader("X-Trace-Id", MDC.get(TRACE_ID_KEY));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        Map<String, String> headers =
                type == ProblemType.SERVICE_UNAVAILABLE ? Map.of("Retry-After", TRANSIENT_RETRY_SECONDS) : Map.of();
        problems.write(request, response, type, null, Map.of(), headers);
    }

    private static void logCompleted(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object user = request.getAttribute(USER_ATTRIBUTE);
        log.atInfo()
                .addKeyValue("method", request.getMethod())
                .addKeyValue("uri", template != null ? template.toString() : UNMATCHED)
                .addKeyValue("status", response.getStatus())
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("user", user != null ? user.toString() : "anonymous")
                .addKeyValue("clientIp", request.getRemoteAddr())
                .log("http request completed");
    }

    private static String randomTraceId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
