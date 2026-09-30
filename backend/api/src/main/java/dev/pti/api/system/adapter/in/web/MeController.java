package dev.pti.api.system.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.system.application.GetCurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-61 {@code GET /me} (DOC-32): anonymous callers get {@code authenticated: false}, not a 401. */
@RestController
class MeController {

    private final GetCurrentUser getCurrentUser;

    MeController(GetCurrentUser getCurrentUser) {
        this.getCurrentUser = getCurrentUser;
    }

    @GetMapping(ApiPaths.V1 + "/me")
    @Operation(
            operationId = "getCurrentUser",
            summary = "Who the caller is: username, display name, effective roles and token expiry")
    @ApiResponse(
            responseCode = "200",
            description = "The caller",
            content = @Content(examples = @ExampleObject(name = "operator", value = """
                                                    {"authenticated": true, "username": "operator", "displayName": "Demo Operator", "roles": ["operator", "viewer"], "tokenExpiresAt": "2026-09-29T21:24:35Z"}
                                                    """)))
    CurrentUserResponse getCurrentUser(Caller caller) {
        return CurrentUserResponse.from(getCurrentUser.execute(caller));
    }
}
