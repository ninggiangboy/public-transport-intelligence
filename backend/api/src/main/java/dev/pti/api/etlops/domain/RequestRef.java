package dev.pti.api.etlops.domain;

import java.util.UUID;

/** The replay or job request a run came from (DOC-32 E-32). {@code type} is {@code replay} or {@code job}. */
public record RequestRef(String type, UUID id) {}
