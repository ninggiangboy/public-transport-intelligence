package dev.pti.api.transit.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** An upcoming call at a stop with its predicted time and how far the prediction can be trusted (DOC-32 E-08). */
public record Arrival(
        String tripId,
        String routeId,
        int directionId,
        @Nullable String headsign,
        LocalDate serviceDate,
        Instant scheduledArrival,
        Instant predictedArrival,
        int predictedDelaySeconds,
        int sampleCount,
        Confidence confidence,
        @Nullable Instant realtimeArrival) {}
