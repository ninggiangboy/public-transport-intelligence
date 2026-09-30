package dev.pti.api.platform.adapter.in.ratelimit;

import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter.Bucket;
import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter.Decision;
import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits REST requests (DOC-31 §11). It sits in the security chain after authorization, so the order of checks is
 * authentication (401), authorization (403), then this (429): a caller who may not call an endpoint spends no tokens.
 * A request with a valid token draws on the {@code authenticated} bucket of its {@code sub}; one without a token on the
 * {@code public} bucket of the client IP. {@code POST} and {@code PUT} also draw on the {@code write} bucket.
 *
 * <p>The IP is {@code request.getRemoteAddr()}: Tomcat's remote IP valve replaces it by {@code X-Forwarded-For} only
 * when the request comes from a trusted proxy ({@code server.tomcat.remoteip.internal-proxies}), so a client cannot
 * pick its own bucket.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimiter limiter, ProblemWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication instanceof JwtAuthenticationToken;
        String key = authenticated
                ? ((JwtAuthenticationToken) authentication).getToken().getSubject()
                : request.getRemoteAddr();
        if (key == null) {
            key = request.getRemoteAddr();
        }
        Decision decision = limiter.tryConsume(authenticated ? Bucket.AUTHENTICATED : Bucket.PUBLIC, key);
        if (decision.allowed() && isWrite(request)) {
            decision = limiter.tryConsume(Bucket.WRITE, key);
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            problems.write(
                    request,
                    response,
                    ProblemType.RATE_LIMITED,
                    null,
                    Map.of("retryAfterSeconds", decision.retryAfterSeconds()),
                    Map.of("Retry-After", String.valueOf(decision.retryAfterSeconds())));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isWrite(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method);
    }
}
