package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.application.port.StopRoutesReader;
import dev.pti.api.transit.domain.Stop;
import dev.pti.api.transit.domain.StopMatch;
import dev.pti.api.transit.domain.StopRoutes;
import dev.pti.common.tx.TransactionRunner;
import java.util.List;

/**
 * {@code GET /stops} (DOC-32 E-06): stops by name or code, or the stops in a map window or of a route. Every stop
 * comes with the routes that call at it.
 */
public final class SearchStops {

    private final RequireActiveFeed requireActiveFeed;
    private final StopReader stops;
    private final StopRoutesReader stopRoutes;
    private final TransactionRunner tx;

    public SearchStops(
            RequireActiveFeed requireActiveFeed, StopReader stops, StopRoutesReader stopRoutes, TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.stops = stops;
        this.stopRoutes = stopRoutes;
        this.tx = tx;
    }

    /** The page is complete for a text search ({@code next} is always {@code null}); keyset-paged otherwise. */
    public WithAsOf<Page<StopMatch>> execute(StopSearch search) {
        ActiveFeed feed = requireActiveFeed.execute();
        Page<StopMatch> page = tx.inTransaction(() -> switch (search) {
            case StopSearch.ByText text -> byText(feed, text);
            case StopSearch.ByArea area -> byArea(feed, area);
        });
        return new WithAsOf<>(page, feed.activatedAt());
    }

    private Page<StopMatch> byText(ActiveFeed feed, StopSearch.ByText search) {
        List<Stop> found = stops.searchText(feed, search.q(), search.limit());
        return new Page<>(matches(feed, found), null);
    }

    private Page<StopMatch> byArea(ActiveFeed feed, StopSearch.ByArea search) {
        List<String> routeStopIds =
                search.routeId() != null ? stopRoutes.read(feed).stopsOf(search.routeId()) : null;
        PageRequest page = search.page();
        if (routeStopIds != null && routeStopIds.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        KeysetCursor cursor = page.after();
        String after = cursor != null ? cursor.keys().get(0) : null;
        List<Stop> found = stops.searchArea(
                feed, new StopReader.AreaQuery(search.bbox(), search.routeId(), routeStopIds, after, page.fetchSize()));
        Page<Stop> paged = Page.of(page, found, stop -> List.of(stop.stopId()));
        KeysetCursor next = paged.next();
        return new Page<>(matches(feed, paged.items()), next);
    }

    private List<StopMatch> matches(ActiveFeed feed, List<Stop> found) {
        StopRoutes routes = stopRoutes.read(feed);
        return found.stream()
                .map(stop -> new StopMatch(stop, routes.routeIdsOf(stop.stopId())))
                .toList();
    }
}
