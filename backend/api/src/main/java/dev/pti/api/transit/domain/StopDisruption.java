package dev.pti.api.transit.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An open disruption alert on a route that calls at a stop (DOC-32 E-07). */
public record StopDisruption(
        String alertId,
        @Nullable String disruptionId,
        String routeId,
        @Nullable Integer directionId,
        int severity,
        String title,
        @Nullable Instant startedAt) {}
