package dev.pti.analytics.recompute.application.port;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import java.time.Instant;
import java.util.List;

/**
 * Which routes a recompute has to visit (DOC-23 §11.1): the ones with source rows in the range and the ones with an
 * episode that touches it. The second kind matters because a recompute that finds no support for an episode has to
 * delete it.
 */
public interface RecomputeScopes {

    /** Routes with a {@code fact_vehicle_position} row with {@code from ≤ event time ≤ to}; sorted. */
    List<String> routesWithPositions(Instant from, Instant to, DateRange serviceDates);

    /** Routes with a {@code fact_trip_update} row with {@code from ≤ event time ≤ to}; sorted. */
    List<String> routesWithTripUpdates(Instant from, Instant to, DateRange serviceDates);

    /** Routes with a bunching episode that starts by {@code to} and is open or ends at or after {@code from}. */
    List<String> routesWithBunchingEpisodes(Instant from, Instant to);

    /** As {@link #routesWithBunchingEpisodes} for disruption episodes. */
    List<String> routesWithDisruptionEpisodes(Instant from, Instant to);
}
