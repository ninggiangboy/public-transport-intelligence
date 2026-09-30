package dev.pti.api.platform.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling (DOC-27 §3.2): anonymous, or the holder of a valid token. Built by the web adapter from the security
 * context and handed to use cases that decide by role (the bunching overlay of {@code /vehicles/live}, the audience of
 * alerts) or that record an actor.
 *
 * <p>{@code roles} are the effective roles after the hierarchy, so an operator also has {@link Role#VIEWER}.
 */
public record Caller(
        @Nullable String username,
        @Nullable String displayName,
        Set<Role> roles,
        @Nullable Instant tokenExpiresAt) {

    private static final Caller ANONYMOUS = new Caller(null, null, Set.of(), null);

    public Caller {
        roles = roles.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        if (username == null && !roles.isEmpty()) {
            throw new IllegalArgumentException("An anonymous caller cannot hold roles");
        }
    }

    public static Caller anonymous() {
        return ANONYMOUS;
    }

    public boolean authenticated() {
        return username != null;
    }

    /** True for a viewer and for an operator. */
    public boolean isViewer() {
        return roles.contains(Role.VIEWER);
    }

    public boolean isOperator() {
        return roles.contains(Role.OPERATOR);
    }

    /**
     * The audit actor written to {@code requested_by}, {@code resolved_by}, {@code acknowledged_by}… (DOC-27 §3.2,
     * DOC-15 CHECK {@code ^user:.+$}).
     *
     * @throws IllegalStateException for an anonymous caller: only authenticated endpoints write
     */
    public String actor() {
        if (username == null) {
            throw new IllegalStateException("An anonymous caller has no actor");
        }
        return "user:" + username;
    }
}
