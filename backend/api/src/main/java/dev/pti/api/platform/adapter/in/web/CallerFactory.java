package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Role;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the {@link Caller} from the security context: the token's {@code preferred_username}, its {@code name}
 * claim, its expiry and the roles it holds after the hierarchy, so an operator is also a viewer (DOC-27 §3.2).
 */
public final class CallerFactory {

    private final RoleHierarchy hierarchy;

    public CallerFactory(RoleHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public Caller from(@Nullable Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            return Caller.anonymous();
        }
        String username = token.getName();
        if (username == null || username.isBlank()) {
            username = token.getToken().getSubject();
        }
        if (username == null || username.isBlank()) {
            return Caller.anonymous();
        }
        return new Caller(
                username,
                token.getToken().getClaimAsString("name"),
                effectiveRoles(token.getAuthorities()),
                token.getToken().getExpiresAt());
    }

    private Set<Role> effectiveRoles(java.util.Collection<? extends GrantedAuthority> authorities) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority reachable : hierarchy.getReachableGrantedAuthorities(authorities)) {
            for (Role role : Role.values()) {
                if (role.authority().equals(reachable.getAuthority())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
