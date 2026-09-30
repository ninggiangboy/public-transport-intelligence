package dev.pti.api.platform.application;

import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ServiceUnavailableException;

/**
 * The ACTIVE feed a GTFS query must use, or 503 when the system has no feed yet (DOC-31 §10.2). Use cases of the
 * transport group call it once at the start of a request and pass the result to every query of that request, so the
 * whole response comes from one {@code feedVersionId}.
 */
public final class RequireActiveFeed {

    private final ActiveFeedReader feeds;

    public RequireActiveFeed(ActiveFeedReader feeds) {
        this.feeds = feeds;
    }

    /** @throws ServiceUnavailableException with {@code Retry-After: 30} when no feed is ACTIVE */
    public ActiveFeed execute() {
        return feeds.find().orElseThrow(ServiceUnavailableException::noActiveFeed);
    }
}
