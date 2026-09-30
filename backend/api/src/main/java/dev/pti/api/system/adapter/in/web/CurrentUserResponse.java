package dev.pti.api.system.adapter.in.web;

import dev.pti.api.platform.domain.ApiTime;
import dev.pti.api.system.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response of {@code GET /me} (DOC-32 E-61). */
public record CurrentUserResponse(
        boolean authenticated,
        @Nullable String username,
        @Nullable String displayName,
        List<String> roles,
        @Nullable String tokenExpiresAt) {

    static CurrentUserResponse from(CurrentUser user) {
        Instant expiresAt = user.tokenExpiresAt();
        return new CurrentUserResponse(
                user.authenticated(),
                user.username(),
                user.displayName(),
                user.roles(),
                expiresAt != null ? ApiTime.format(expiresAt) : null);
    }
}
