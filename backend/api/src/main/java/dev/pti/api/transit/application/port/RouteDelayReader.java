package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.OnTimeTolerance;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Aggregates the observed arrivals of a route into delay buckets (DOC-32 E-03). */
public interface RouteDelayReader {

    /**
     * @param from inclusive, on the scheduled arrival
     * @param to exclusive
     * @param directionId {@code null} for both directions
     */
    record Request(
            ActiveFeed feed,
            String routeId,
            BucketSize bucket,
            Instant from,
            Instant to,
            @Nullable Integer directionId,
            OnTimeTolerance tolerance) {}

    /** The buckets in ascending order; a bucket without observations is not in the list. */
    List<DelayBucket> read(Request request);
}
