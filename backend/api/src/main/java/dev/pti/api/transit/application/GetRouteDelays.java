package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.application.port.RouteDelayReader;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.OnTimeTolerance;
import dev.pti.api.transit.domain.RouteDelays;
import dev.pti.common.tx.TransactionRunner;
import java.util.List;

/**
 * {@code GET /routes/{routeId}/delays} (DOC-32 E-03): the delay observed on a route over time, for the hour by weekday
 * heatmap and the delay chart of the route scorecard. On time means the same as in OTP (DOC-23 §8.1).
 */
public final class GetRouteDelays {

    private final RequireActiveFeed requireActiveFeed;
    private final RouteCatalogReader routes;
    private final RouteDelayReader delays;
    private final DataAsOfReader asOf;
    private final OnTimeTolerance tolerance;
    private final TransactionRunner tx;

    public GetRouteDelays(
            RequireActiveFeed requireActiveFeed,
            RouteCatalogReader routes,
            RouteDelayReader delays,
            DataAsOfReader asOf,
            OnTimeTolerance tolerance,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.routes = routes;
        this.delays = delays;
        this.asOf = asOf;
        this.tolerance = tolerance;
        this.tx = tx;
    }

    /** @throws NotFoundException when the ACTIVE feed has no such route */
    public WithAsOf<RouteDelays> execute(RouteDelaysQuery query) {
        ActiveFeed feed = requireActiveFeed.execute();
        List<DelayBucket> buckets = tx.inTransaction(() -> {
            if (!routes.read(feed).contains(query.routeId())) {
                throw new NotFoundException("The route does not exist in the active feed.");
            }
            return delays.read(new RouteDelayReader.Request(
                    feed, query.routeId(), query.bucket(), query.from(), query.to(), query.directionId(), tolerance));
        });
        RouteDelays result =
                new RouteDelays(query.routeId(), query.bucket(), query.from(), query.to(), tolerance, buckets);
        return WithAsOf.of(result, asOf.asOf(AsOfKind.TRIP_UPDATE));
    }
}
