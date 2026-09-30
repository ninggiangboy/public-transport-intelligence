package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 as Problem Details (DOC-30 §3.2): no token, or a token that is expired, badly signed, for another issuer or
 * audience. The answer never says which check failed; the log line carries no token (DOC-30 §4.2).
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

    private final ProblemWriter problems;

    public ProblemAuthenticationEntryPoint(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        boolean invalidToken = exception instanceof OAuth2AuthenticationException;
        log.info("Authentication required for {} {}", request.getMethod(), request.getRequestURI());
        problems.write(
                request,
                response,
                ProblemType.UNAUTHORIZED,
                invalidToken ? "The access token is invalid or has expired." : null,
                Map.of(),
                Map.of("WWW-Authenticate", invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer"));
    }
}
