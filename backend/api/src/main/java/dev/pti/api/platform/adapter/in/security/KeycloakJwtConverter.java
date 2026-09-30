package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.domain.Role;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Reads the roles of a Keycloak token (DOC-27 §3.2): the realm roles in {@code realm_access.roles} that the API knows,
 * {@code viewer} and {@code operator}, become {@code ROLE_VIEWER} and {@code ROLE_OPERATOR}; every other role
 * ({@code offline_access}, {@code default-roles-pti}, a client role) is ignored. The principal name is {@code
 * preferred_username}, or {@code sub} when a token has none.
 */
public final class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";
    static final String PRINCIPAL_CLAIM = "preferred_username";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String name = jwt.getClaimAsString(PRINCIPAL_CLAIM);
        return new JwtAuthenticationToken(
                jwt, authorities(jwt), name != null && !name.isBlank() ? name : jwt.getSubject());
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess instanceof Map<?, ?> access && access.get(ROLES) instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name) {
                    Role.fromWireName(name)
                            .ifPresent(known -> authorities.add(new SimpleGrantedAuthority(known.authority())));
                }
            }
        }
        return authorities;
    }
}
