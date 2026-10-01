package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.DeadLetterResponses.DeadLetterDetailResponse;
import dev.pti.api.etlops.adapter.in.web.ReplayResponses.ReplayResponse;
import dev.pti.api.etlops.application.ConfirmDeadLetterReplay;
import dev.pti.api.etlops.application.DiscardDeadLetter;
import dev.pti.api.etlops.application.EditDeadLetterPayload;
import dev.pti.api.etlops.application.ReplayDeadLetter;
import dev.pti.api.etlops.application.ResolveDeadLetter;
import dev.pti.api.etlops.application.Submitted;
import dev.pti.api.etlops.domain.IdempotencyKey;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.domain.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-43 {@code PUT /etl/dlq/{id}/payload}, E-44 {@code replay}, E-45 {@code confirm}, E-46 {@code discard} and E-47 {@code
 * resolve} (DOC-32 §7): what an operator does with a dead letter. Each is one conditional change in one transaction on
 * the {@code operator} datasource with a line in the action log; a dead letter in the wrong status is a 409 {@code
 * dlq-invalid-state} with its {@code currentStatus}. Replay and confirm take an {@code Idempotency-Key} and answer 202
 * with the replay request; the others answer 200 with the dead letter.
 */
@RestController
class DeadLetterCommandController {

    private static final String DEAD_LETTER_EXAMPLE = """
            {"id": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d", "source": "GTFS_RT_VEHICLE_POSITION", "stage": "SCHEMA", "ruleId": "DQ-01", "errorClass": "SchemaViolationException", "errorMessage": "$.payload.position.latitude: must be between -90 and 90", "status": "DISCARDED", "hasEditedPayload": false, "replayCount": 0, "autoReplayCount": 0, "createdAt": "2026-09-29T21:18:47Z", "updatedAt": "2026-09-29T21:40:00Z", "rawPayload": "{}", "batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d", "triageAttempts": 1, "resolvedBy": "user:operator", "resolvedAt": "2026-09-29T21:40:00Z", "actions": [], "replays": [], "allowedActions": []}
            """;

    private static final String REPLAY_EXAMPLE = """
            {"id": "0192f5c0-0000-7000-8000-000000000001", "kind": "DLQ_RECORD", "source": "GTFS_RT_VEHICLE_POSITION", "deadLetterId": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d", "status": "PENDING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T21:30:00Z"}
            """;

    /** The body of E-46: why the dead letter is given up. */
    record DiscardBody(@NotNull @Size(min = 3, max = 500) String reason) {}

    /** The body of E-47: how it was handled outside the system. */
    record ResolveBody(@NotNull @Size(min = 3, max = 500) String note) {}

    private final EditDeadLetterPayload editDeadLetterPayload;
    private final ReplayDeadLetter replayDeadLetter;
    private final ConfirmDeadLetterReplay confirmDeadLetterReplay;
    private final DiscardDeadLetter discardDeadLetter;
    private final ResolveDeadLetter resolveDeadLetter;

    DeadLetterCommandController(
            EditDeadLetterPayload editDeadLetterPayload,
            ReplayDeadLetter replayDeadLetter,
            ConfirmDeadLetterReplay confirmDeadLetterReplay,
            DiscardDeadLetter discardDeadLetter,
            ResolveDeadLetter resolveDeadLetter) {
        this.editDeadLetterPayload = editDeadLetterPayload;
        this.replayDeadLetter = replayDeadLetter;
        this.confirmDeadLetterReplay = confirmDeadLetterReplay;
        this.discardDeadLetter = discardDeadLetter;
        this.resolveDeadLetter = resolveDeadLetter;
    }

    @PutMapping(ApiPaths.V1 + "/etl/dlq/{id}/payload")
    @Operation(
            operationId = "editDeadLetterPayload",
            summary = "Store a corrected payload for a dead letter (operator)")
    @ApiResponse(
            responseCode = "200",
            description = "The dead letter with the edited payload; raw_payload is never changed",
            content = @Content(examples = @ExampleObject(name = "deadLetter", value = DEAD_LETTER_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    @ApiResponse(responseCode = "409", description = "dlq-invalid-state: not NEW, TRIAGED, PENDING_CONFIRM or MANUAL")
    @ApiResponse(responseCode = "422", description = "invalid-payload, pii-not-allowed or business-key-changed")
    DeadLetterDetailResponse editDeadLetterPayload(
            Caller caller, @PathVariable String id, @RequestBody String payload) {
        return DeadLetterDetailResponse.from(
                editDeadLetterPayload.execute(caller, RequestValues.uuidOrNotFound(id, "The dead letter"), payload));
    }

    @PostMapping(ApiPaths.V1 + "/etl/dlq/{id}/replay")
    @Operation(
            operationId = "replayDeadLetter",
            summary = "Replay a NEW or MANUAL dead letter (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The replay request is stored; etl-batch runs it. Location is the replay",
            content = @Content(examples = @ExampleObject(name = "replay", value = REPLAY_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    @ApiResponse(
            responseCode = "409",
            description = "dlq-invalid-state, or replay-already-running with existingReplayId")
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused")
    ResponseEntity<ReplayResponse> replayDeadLetter(
            Caller caller,
            @PathVariable String id,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey) {
        return accepted(replayDeadLetter.execute(
                caller, RequestValues.uuidOrNotFound(id, "The dead letter"), IdempotencyKey.check(idempotencyKey)));
    }

    @PostMapping(ApiPaths.V1 + "/etl/dlq/{id}/confirm")
    @Operation(
            operationId = "confirmDeadLetterReplay",
            summary = "Confirm the replay of a PENDING_CONFIRM dead letter (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The replay request is stored; the action log gets CONFIRMED, then REPLAY_REQUESTED",
            content = @Content(examples = @ExampleObject(name = "replay", value = REPLAY_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    @ApiResponse(
            responseCode = "409",
            description = "dlq-invalid-state, or replay-already-running with existingReplayId")
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused")
    ResponseEntity<ReplayResponse> confirmDeadLetterReplay(
            Caller caller,
            @PathVariable String id,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey) {
        return accepted(confirmDeadLetterReplay.execute(
                caller, RequestValues.uuidOrNotFound(id, "The dead letter"), IdempotencyKey.check(idempotencyKey)));
    }

    @PostMapping(ApiPaths.V1 + "/etl/dlq/{id}/discard")
    @Operation(
            operationId = "discardDeadLetter",
            summary = "Give up on a NEW, MANUAL or PENDING_CONFIRM dead letter (operator)")
    @ApiResponse(
            responseCode = "200",
            description = "The dead letter, DISCARDED. Discarded again by the same person it is the same answer",
            content = @Content(examples = @ExampleObject(name = "deadLetter", value = DEAD_LETTER_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    @ApiResponse(responseCode = "409", description = "dlq-invalid-state")
    DeadLetterDetailResponse discardDeadLetter(
            Caller caller, @PathVariable String id, @Valid @RequestBody DiscardBody body) {
        return DeadLetterDetailResponse.from(
                discardDeadLetter.execute(caller, RequestValues.uuidOrNotFound(id, "The dead letter"), body.reason()));
    }

    @PostMapping(ApiPaths.V1 + "/etl/dlq/{id}/resolve")
    @Operation(
            operationId = "resolveDeadLetter",
            summary = "Mark a MANUAL dead letter as handled outside the system (operator)")
    @ApiResponse(
            responseCode = "200",
            description = "The dead letter, RESOLVED. Resolved again by the same person it is the same answer",
            content = @Content(examples = @ExampleObject(name = "deadLetter", value = DEAD_LETTER_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    @ApiResponse(responseCode = "409", description = "dlq-invalid-state")
    DeadLetterDetailResponse resolveDeadLetter(
            Caller caller, @PathVariable String id, @Valid @RequestBody ResolveBody body) {
        return DeadLetterDetailResponse.from(
                resolveDeadLetter.execute(caller, RequestValues.uuidOrNotFound(id, "The dead letter"), body.note()));
    }

    private static ResponseEntity<ReplayResponse> accepted(Submitted<ReplayRequest> submitted) {
        ReplayRequest request = submitted.value();
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/etl/replays/" + request.id()))
                .body(ReplayResponse.from(request, false));
    }
}
