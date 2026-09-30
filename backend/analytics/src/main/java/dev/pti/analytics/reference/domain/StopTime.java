package dev.pti.analytics.reference.domain;

import org.jspecify.annotations.Nullable;

/** A {@code gtfs_stop_time} row joined with the coordinates of its stop: the input of {@link TripPattern}. */
public record StopTime(
        int stopSequence,
        String stopId,
        int arrivalSeconds,
        int departureSeconds,
        @Nullable Double shapeDistTraveled,
        double lat,
        double lon) {}
