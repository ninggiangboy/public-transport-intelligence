package dev.pti.api.etlops.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The replay or job request a run came from (DOC-32 E-30, E-32). {@code type} is {@code replay} or {@code job};
 * {@code requestedBy} is the actor that sent it ({@code user:<name>}), absent for a request written without one.
 */
public record RequestRef(String type, UUID id, @Nullable String requestedBy) {}
