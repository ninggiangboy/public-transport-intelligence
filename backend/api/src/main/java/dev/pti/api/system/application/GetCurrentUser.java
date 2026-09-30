package dev.pti.api.system.application;

import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.system.domain.CurrentUser;
import java.util.Comparator;
import java.util.List;

/**
 * {@code GET /me} (DOC-32 E-61): who the caller is, for the menus of the frontend. It is no substitute for the checks
 * the server makes on every request.
 */
public final class GetCurrentUser {

    public CurrentUser execute(Caller caller) {
        if (!caller.authenticated()) {
            return CurrentUser.anonymous();
        }
        List<String> roles = caller.roles().stream()
                .map(Role::wireName)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new CurrentUser(true, caller.username(), caller.displayName(), roles, caller.tokenExpiresAt());
    }
}
