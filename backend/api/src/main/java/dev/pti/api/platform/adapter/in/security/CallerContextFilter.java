package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.web.ApiRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells the request log who the caller is (DOC-31 §13): once the bearer token is read, the caller's name goes into a
 * request attribute that {@code ApiRequestFilter} logs when the request ends, because the security context is gone by
 * then.
 */
public final class CallerContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null) {
            request.setAttribute(ApiRequestFilter.USER_ATTRIBUTE, authentication.getName());
        }
        chain.doFilter(request, response);
    }
}
