package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.OpsResponses.RuntimeFlagResponse;
import dev.pti.api.etlops.application.GetRuntimeFlag;
import dev.pti.api.etlops.application.ListRuntimeFlags;
import dev.pti.api.etlops.application.UpdateRuntimeFlag;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.ItemsResponse;
import dev.pti.api.platform.domain.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-55 {@code GET /etl/flags}, E-56 {@code GET /etl/flags/{key}} and E-57 {@code PUT /etl/flags/{key}} (DOC-32 §9): the
 * runtime flags that pause listeners and switch triage on and off. The services read a change within five seconds.
 */
@RestController
class RuntimeFlagController {

    private static final String FLAG_EXAMPLE = """
            {"key": "etl.consumer.gtfs-rt.paused", "value": false, "description": "Pause the GTFS-realtime listeners (vehicle positions and trip updates).", "updatedBy": "migration", "updatedAt": "2026-09-27T08:30:00Z"}
            """;

    /** The body of E-57: a JSON boolean, number or string of the type the flag already has. */
    record FlagBody(@NotNull Object value) {}

    private final ListRuntimeFlags listRuntimeFlags;
    private final GetRuntimeFlag getRuntimeFlag;
    private final UpdateRuntimeFlag updateRuntimeFlag;

    RuntimeFlagController(
            ListRuntimeFlags listRuntimeFlags, GetRuntimeFlag getRuntimeFlag, UpdateRuntimeFlag updateRuntimeFlag) {
        this.listRuntimeFlags = listRuntimeFlags;
        this.getRuntimeFlag = getRuntimeFlag;
        this.updateRuntimeFlag = updateRuntimeFlag;
    }

    @GetMapping(ApiPaths.V1 + "/etl/flags")
    @Operation(operationId = "listRuntimeFlags", summary = "Every runtime flag, ordered by key (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The flags",
            content =
                    @Content(examples = @ExampleObject(name = "flags", value = "{\"items\": [" + FLAG_EXAMPLE + "]}")))
    ItemsResponse<RuntimeFlagResponse> listRuntimeFlags() {
        return new ItemsResponse<>(listRuntimeFlags.execute().stream()
                .map(RuntimeFlagResponse::from)
                .toList());
    }

    @GetMapping(ApiPaths.V1 + "/etl/flags/{key}")
    @Operation(operationId = "getRuntimeFlag", summary = "One runtime flag (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The flag",
            content = @Content(examples = @ExampleObject(name = "flag", value = FLAG_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such flag")
    RuntimeFlagResponse getRuntimeFlag(@PathVariable String key) {
        return RuntimeFlagResponse.from(getRuntimeFlag.execute(key));
    }

    @PutMapping(ApiPaths.V1 + "/etl/flags/{key}")
    @Operation(operationId = "updateRuntimeFlag", summary = "Set a runtime flag (operator)")
    @ApiResponse(
            responseCode = "200",
            description =
                    "The flag after the change; the same value changes nothing. Services pick it up within 5 seconds",
            content = @Content(examples = @ExampleObject(name = "flag", value = FLAG_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such flag; the API does not create flags")
    @ApiResponse(
            responseCode = "422",
            description = "invalid-flag-value: the type differs from the flag's current value")
    RuntimeFlagResponse updateRuntimeFlag(Caller caller, @PathVariable String key, @Valid @RequestBody FlagBody body) {
        return RuntimeFlagResponse.from(updateRuntimeFlag.execute(caller, key, body.value()));
    }
}
