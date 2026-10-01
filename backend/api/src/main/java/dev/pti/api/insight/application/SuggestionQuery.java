package dev.pti.api.insight.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The filters of the dispatch suggestion list (DOC-32 E-17): {@code created_at} in {@code [from, to)}, some routes, one
 * bunching episode, and the feedback: {@code accepted}, {@code ignored}, or {@code none} for no feedback yet.
 */
public record SuggestionQuery(
        Instant from,
        Instant to,
        List<String> routeIds,
        @Nullable UUID bunchingId,
        @Nullable String feedback) {

    public SuggestionQuery {
        routeIds = List.copyOf(routeIds);
    }
}
