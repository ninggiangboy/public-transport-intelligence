package dev.pti.api.transit.application;

import dev.pti.api.transit.domain.BucketSize;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What {@code GET /routes/{routeId}/delays} is asked for, with the range resolved to instants by the web adapter
 * (defaults, relative times and the range limits are a matter of the request's format).
 *
 * @param from inclusive
 * @param to exclusive
 * @param directionId {@code null} for both directions
 */
public record RouteDelaysQuery(
        String routeId,
        Instant from,
        Instant to,
        BucketSize bucket,
        @Nullable Integer directionId) {

    public RouteDelaysQuery {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (directionId != null && directionId != 0 && directionId != 1) {
            throw new IllegalArgumentException("directionId must be 0 or 1: " + directionId);
        }
    }
}
