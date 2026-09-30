package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.security.EndpointRules.Access;
import dev.pti.api.platform.adapter.in.security.EndpointRules.Rule;
import dev.pti.api.platform.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Applies {@link EndpointRules}: the first rule that matches the request decides whether the caller may pass
 * (DOC-27 §4). A request that matches no rule is denied, so a controller that nobody declared in the matrix is shut
 * rather than open, with one refinement: when no handler exists at all for the path, an authenticated caller is let
 * through to get a plain 404, while an anonymous caller is asked to authenticate and learns nothing about the path.
 */
public final class EndpointAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final List<Rule> rules;
    private final List<RequestMatcher> matchers;
    private final RoleHierarchy hierarchy;
    private final Predicate<HttpServletRequest> hasHandler;

    /**
     * @param hasHandler whether Spring MVC has a handler for the request, for the 404 of undeclared paths
     */
    public EndpointAuthorizationManager(
            List<Rule> rules, RoleHierarchy hierarchy, Predicate<HttpServletRequest> hasHandler) {
        this.rules = List.copyOf(rules);
        this.matchers = rules.stream().map(Rule::matcher).toList();
        this.hierarchy = hierarchy;
        this.hasHandler = hasHandler;
    }

    @Override
    public @Nullable AuthorizationResult authorize(
            Supplier<? extends @Nullable Authentication> authentication, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        Authentication current = authentication.get();
        boolean authenticated = isAuthenticated(current);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (rule.matches(request, matchers.get(i))) {
                return new AuthorizationDecision(allows(rule.access(), current, authenticated));
            }
        }
        return new AuthorizationDecision(authenticated && !hasHandler.test(request));
    }

    private boolean allows(Access access, @Nullable Authentication authentication, boolean authenticated) {
        return switch (access) {
            case ANONYMOUS -> true;
            case VIEWER -> authenticated && holds(authentication, Role.VIEWER.authority());
            case OPERATOR -> authenticated && holds(authentication, Role.OPERATOR.authority());
            case WEBHOOK -> authenticated && holds(authentication, WebhookTokenFilter.AUTHORITY);
        };
    }

    private boolean holds(@Nullable Authentication authentication, String authority) {
        if (authentication == null) {
            return false;
        }
        Set<String> reachable = hierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return reachable.contains(authority);
    }

    private static boolean isAuthenticated(@Nullable Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
