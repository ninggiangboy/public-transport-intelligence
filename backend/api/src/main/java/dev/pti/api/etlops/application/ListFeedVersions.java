package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.FeedVersionReader;
import dev.pti.api.etlops.domain.FeedVersion;
import dev.pti.common.tx.TransactionRunner;
import java.util.List;

/** {@code GET /etl/feeds} (DOC-32 E-38): the 50 newest GTFS feed versions, for the feed screen. */
public final class ListFeedVersions {

    static final int LIMIT = 50;

    private final FeedVersionReader feeds;
    private final TransactionRunner tx;

    public ListFeedVersions(FeedVersionReader feeds, TransactionRunner tx) {
        this.feeds = feeds;
        this.tx = tx;
    }

    public List<FeedVersion> execute() {
        return tx.inTransaction(() -> feeds.latest(LIMIT));
    }
}
