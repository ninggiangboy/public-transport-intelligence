package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.application.port.StopDisruptionReader;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.application.port.StopRoutesReader;
import dev.pti.api.transit.domain.RouteSummary;
import dev.pti.api.transit.domain.Stop;
import dev.pti.api.transit.domain.StopDetail;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopRoute;
import dev.pti.api.transit.domain.StopRouteRef;
import dev.pti.common.events.Audience;
import dev.pti.common.tx.TransactionRunner;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /stops/{stopId}} (DOC-32 E-07): a stop, the routes that call at it, and the disruptions open on those
 * routes. The disruptions are the only part that depends on who asks: an anonymous caller sees the {@code PUBLIC}
 * ones, a viewer all of them (ADR-0023).
 */
public final class GetStop {

    private final RequireActiveFeed requireActiveFeed;
    private final StopReader stops;
    private final StopRoutesReader stopRoutes;
    private final RouteCatalogReader routes;
    private final StopDisruptionReader disruptions;
    private final TransactionRunner tx;

    public GetStop(
            RequireActiveFeed requireActiveFeed,
            StopReader stops,
            StopRoutesReader stopRoutes,
            RouteCatalogReader routes,
            StopDisruptionReader disruptions,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.stops = stops;
        this.stopRoutes = stopRoutes;
        this.routes = routes;
        this.disruptions = disruptions;
        this.tx = tx;
    }

    /** @throws NotFoundException when the ACTIVE feed has no such stop */
    public WithAsOf<StopDetail> execute(Caller caller, String stopId) {
        ActiveFeed feed = requireActiveFeed.execute();
        StopDetail detail = tx.inTransaction(() -> {
            Stop stop = stops.find(feed, stopId)
                    .orElseThrow(() -> new NotFoundException("The stop does not exist in the active feed."));
            List<StopRoute> served = servedRoutes(feed, stopId);
            List<String> routeIds = served.stream().map(StopRoute::routeId).toList();
            List<StopDisruption> open =
                    routeIds.isEmpty() ? List.of() : disruptions.findOpen(routeIds, audiencesOf(caller));
            return new StopDetail(stop, served, open);
        });
        return new WithAsOf<>(detail, feed.activatedAt());
    }

    private List<StopRoute> servedRoutes(ActiveFeed feed, String stopId) {
        Map<String, StopRouteRef> refs = new HashMap<>();
        stopRoutes.read(feed).routesOf(stopId).forEach(ref -> refs.put(ref.routeId(), ref));
        List<StopRoute> served = new ArrayList<>();
        // The order of the route picker; a route that is no longer in the catalog cannot be described, so it is left
        // out.
        for (RouteSummary route : routes.read(feed).routes()) {
            StopRouteRef ref = refs.get(route.routeId());
            if (ref != null) {
                served.add(new StopRoute(
                        route.routeId(), route.displayName(), route.color(), route.textColor(), ref.headsigns()));
            }
        }
        return served;
    }

    private static Set<Audience> audiencesOf(Caller caller) {
        return caller.isViewer() ? EnumSet.allOf(Audience.class) : EnumSet.of(Audience.PUBLIC);
    }
}
