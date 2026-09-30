package dev.pti.api.transit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The delay profile of a direction for one weekday and hour (DOC-32 E-04). {@code windowStart}, {@code windowEnd} and
 * {@code computedAt} come from the row computed last; they are absent when no stop has a row.
 */
public record DelayProfile(
        String routeId,
        int directionId,
        int dayOfWeek,
        int hourOfDay,
        @Nullable LocalDate windowStart,
        @Nullable LocalDate windowEnd,
        @Nullable Instant computedAt,
        List<DelayProfileStop> stops) {

    public DelayProfile {
        stops = List.copyOf(stops);
    }
}
