package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** The short form of a suggestion inside a bunching episode of a list (DOC-32 E-10). */
public record SuggestionRef(UUID id, String action, BigDecimal actionConfidence) {}
