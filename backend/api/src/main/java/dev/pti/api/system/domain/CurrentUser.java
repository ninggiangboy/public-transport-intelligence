package dev.pti.api.system.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Who the token says the caller is (DOC-32 E-61). {@code roles} are the effective roles, sorted by name. */
public record CurrentUser(
        boolean authenticated,
        @Nullable String username,
        @Nullable String displayName,
        List<String> roles,
        @Nullable Instant tokenExpiresAt) {

    public CurrentUser {
        roles = List.copyOf(roles);
    }

    public static CurrentUser anonymous() {
        return new CurrentUser(false, null, null, List.of(), null);
    }
}
