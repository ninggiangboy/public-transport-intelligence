package dev.pti.api.transit.domain;

import java.time.Instant;
import java.util.List;

/** Observed delays of a route over a range, bucketed (DOC-32 E-03). */
public record RouteDelays(
        String routeId,
        BucketSize bucket,
        Instant from,
        Instant to,
        OnTimeTolerance tolerance,
        List<DelayBucket> buckets) {

    public RouteDelays {
        buckets = List.copyOf(buckets);
    }
}
