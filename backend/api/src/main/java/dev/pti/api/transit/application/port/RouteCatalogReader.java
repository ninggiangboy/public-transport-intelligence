package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.RouteCatalog;

/** The routes of a feed version (DOC-32 E-01). Static data: the implementation may keep it for the life of the feed. */
public interface RouteCatalogReader {

    RouteCatalog read(ActiveFeed feed);
}
