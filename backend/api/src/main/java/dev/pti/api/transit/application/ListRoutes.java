package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.common.tx.TransactionRunner;
import java.util.Set;

/** {@code GET /routes} (DOC-32 E-01): the routes of the ACTIVE feed, optionally of some GTFS route types. */
public final class ListRoutes {

    private final RequireActiveFeed requireActiveFeed;
    private final RouteCatalogReader routes;
    private final TransactionRunner tx;

    public ListRoutes(RequireActiveFeed requireActiveFeed, RouteCatalogReader routes, TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.routes = routes;
        this.tx = tx;
    }

    /**
     * @param routeTypes the {@code route_type}s to keep; every route when empty
     * @return the routes, as fresh as the activation of the feed
     */
    public WithAsOf<RouteCatalog> execute(Set<Integer> routeTypes) {
        ActiveFeed feed = requireActiveFeed.execute();
        RouteCatalog catalog = tx.inTransaction(() -> routes.read(feed));
        return new WithAsOf<>(catalog.ofTypes(routeTypes), feed.activatedAt());
    }
}
