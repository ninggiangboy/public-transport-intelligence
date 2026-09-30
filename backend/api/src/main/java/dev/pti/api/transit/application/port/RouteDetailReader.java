package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.RouteDetail;
import java.util.Optional;

/** A route with the pattern of each direction (DOC-32 E-02), {@code empty} when the feed has no such route. */
public interface RouteDetailReader {

    Optional<RouteDetail> find(ActiveFeed feed, String routeId);
}
