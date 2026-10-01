package dev.pti.api.insight.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The filters of the bunching list (DOC-32 E-10): episodes that intersect {@code [from, to)} in event time, on some
 * routes (all when empty), and in a status ({@code OPEN} or {@code CLOSED}) if given.
 */
public record BunchingQuery(
        Instant from,
        Instant to,
        List<String> routeIds,
        @Nullable String status) {

    public BunchingQuery {
        routeIds = List.copyOf(routeIds);
    }
}
