package dev.pti.analytics.bunching.application.port;

import dev.pti.analytics.bunching.domain.VehiclePosition;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads the vehicle positions that bunching detection works on (DOC-23 §2.2, §5.6): the newest one of a route, for the
 * watermark, and the positions of a window. Every query names the range of service dates that covers its time range
 * ({@link dev.pti.analytics.core.domain.ServiceDates#covering}), which lets the database prune partitions.
 */
public interface VehicleHistoryReader {

    /** The newest event time of a route within the service dates, {@code maxVp(r)} of DOC-23 §2.2. */
    Optional<Instant> newestEventTime(String routeId, DateRange serviceDates);

    /** True when the route has a position with {@code afterExclusive < event time ≤ upToInclusive}. */
    boolean anyPositionIn(String routeId, DateRange serviceDates, Instant afterExclusive, Instant upToInclusive);

    /**
     * The positions of the route, both directions, with {@code afterExclusive < event time ≤ upToInclusive}, ordered
     * by vehicle and event time.
     */
    List<VehiclePosition> positions(
            String routeId, DateRange serviceDates, Instant afterExclusive, Instant upToInclusive);
}
