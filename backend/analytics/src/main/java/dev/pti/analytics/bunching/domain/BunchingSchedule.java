package dev.pti.analytics.bunching.domain;

import dev.pti.analytics.reference.domain.TripPattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The schedule data the evaluator reads: trip patterns and scheduled headways of the ACTIVE feed. The use case
 * implements it on the reference cache; tests implement it with a map.
 */
public interface BunchingSchedule {

    /** Empty for a trip that the ACTIVE feed does not have. */
    Optional<TripPattern> trip(String tripId);

    /**
     * The scheduled headway for a direction at a point in time: the headway of the day type of {@code serviceDate}
     * and the hour of that service day that {@code at} falls in (DOC-23 §2.1). Empty when the feed has none.
     */
    OptionalInt scheduledHeadway(String routeId, int directionId, LocalDate serviceDate, Instant at);
}
