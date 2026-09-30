package dev.pti.api.platform.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import java.util.Optional;

/**
 * Finds the ACTIVE GTFS feed version (DOC-31 §10.2). The implementation behind the port is cached for 30 seconds, so
 * a call costs nothing on the hot path; it is {@code empty} until the first feed is activated.
 */
public interface ActiveFeedReader {

    Optional<ActiveFeed> find();
}
