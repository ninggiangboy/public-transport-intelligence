package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.StopRoutes;

/** Which routes call at which stops in a feed version (DOC-32 E-06). Expensive to build, so built once per feed. */
public interface StopRoutesReader {

    StopRoutes read(ActiveFeed feed);
}
