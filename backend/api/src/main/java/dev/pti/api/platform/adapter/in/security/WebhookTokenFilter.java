package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /internal/**} by the webhook token (DOC-27 §6): {@code Authorization: Bearer <token>} compared
 * in constant time with the token file. No OAuth2 here. A missing or wrong token is a 401 Problem; a right one makes
 * the request the principal {@code alertmanager} with the authority {@link #AUTHORITY}.
 */
public final class WebhookTokenFilter extends OncePerRequestFilter {

    /** The authority that {@link EndpointRules.Access#WEBHOOK} asks for. */
    public static final String AUTHORITY = "ROLE_WEBHOOK";

    private static final String BEARER = "Bearer ";

    private final WebhookToken token;
    private final ProblemWriter problems;

    public WebhookTokenFilter(WebhookToken token, ProblemWriter problems) {
        this.token = token;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!token.matches(bearerToken(request))) {
            problems.write(
                    request, response, ProblemType.UNAUTHORIZED, null, Map.of(), Map.of("WWW-Authenticate", "Bearer"));
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "alertmanager", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        chain.doFilter(request, response);
    }

    private static @Nullable String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length()).strip()
                : null;
    }
}
