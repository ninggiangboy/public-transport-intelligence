package dev.pti.etl.gtfs.domain;

import dev.pti.common.error.PtiException;

/** {@code fetch} found a structural error (GV-01…GV-03): the feed is rejected without loading it. */
public class FeedRejectedException extends PtiException {

    private static final long serialVersionUID = 1L;

    private final FeedCheck check;

    public FeedRejectedException(FeedCheck check, String message) {
        super(message, null);
        this.check = check;
    }

    public FeedCheck check() {
        return check;
    }
}
