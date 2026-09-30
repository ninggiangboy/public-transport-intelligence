package dev.pti.api.platform.domain;

import java.util.Optional;

/**
 * The roles a caller can hold (DOC-27 §3.1). Keycloak realm roles {@code viewer} and {@code operator}; {@code operator}
 * includes {@code viewer} through the role hierarchy, so "viewer" in DOC-32 means a viewer or an operator.
 */
public enum Role {
    VIEWER("viewer"),
    OPERATOR("operator");

    private final String wireName;

    Role(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase name used in tokens and in {@code GET /me}. */
    public String wireName() {
        return wireName;
    }

    /** The Spring Security authority, for example {@code ROLE_VIEWER}. */
    public String authority() {
        return "ROLE_" + name();
    }

    /** The role for a realm role name of a token; {@code empty} for roles the API does not know (they are ignored). */
    public static Optional<Role> fromWireName(String name) {
        for (Role role : values()) {
            if (role.wireName.equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
