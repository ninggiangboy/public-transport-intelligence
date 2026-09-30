package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.RouteDetailReader;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /routes/{routeId}} (DOC-32 E-02): a route with the line and the stops of each direction. */
public final class GetRoute {

    private final RequireActiveFeed requireActiveFeed;
    private final RouteDetailReader details;
    private final TransactionRunner tx;

    public GetRoute(RequireActiveFeed requireActiveFeed, RouteDetailReader details, TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.details = details;
        this.tx = tx;
    }

    /** @throws NotFoundException when the ACTIVE feed has no such route */
    public WithAsOf<RouteDetail> execute(String routeId) {
        ActiveFeed feed = requireActiveFeed.execute();
        RouteDetail detail = tx.inTransaction(() -> details.find(feed, routeId))
                .orElseThrow(() -> new NotFoundException("The route does not exist in the active feed."));
        return new WithAsOf<>(detail, feed.activatedAt());
    }
}
