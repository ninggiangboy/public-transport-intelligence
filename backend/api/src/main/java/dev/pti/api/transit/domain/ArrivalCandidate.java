package dev.pti.api.transit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A scheduled call at a stop in the window of interest, with what the warehouse knows about it (DOC-23 §7.4): the
 * historical delay of its route, stop, weekday and hour, and the state of the trip update at this stop. The formula
 * that turns it into an {@link Arrival} is {@code ListStopArrivals}.
 */
public record ArrivalCandidate(
        LocalDate serviceDate,
        String tripId,
        int stopSequence,
        String routeId,
        int directionId,
        @Nullable String headsign,
        Instant scheduled,
        @Nullable BigDecimal avgDelaySeconds,
        @Nullable Integer sampleCount,
        @Nullable Boolean observed,
        @Nullable String scheduleRelationship,
        @Nullable Instant realtimeTime,
        @Nullable Instant realtimeEventTimestamp) {}
