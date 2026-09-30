package dev.pti.api.platform.adapter.out.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.application.port.FeedChangeListener;
import dev.pti.api.platform.domain.ActiveFeed;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code active-feed} cache in front of the database (DOC-31 §10.2, §10.3): one entry, refreshed when its 30
 * seconds run out, loaded by one caller at a time. Each refresh that finds a different {@code feedVersionId} tells the
 * {@link FeedChangeListener}, which clears the GTFS caches of the old feed.
 */
public final class CachingActiveFeedReader implements ActiveFeedReader {

    private static final String KEY = "active";
    private static final long NO_FEED = -1;

    private final ActiveFeedReader delegate;
    private final Cache<String, Optional<ActiveFeed>> cache;
    private final FeedChangeListener listener;
    private final AtomicLong lastFeedVersionId = new AtomicLong(NO_FEED);

    public CachingActiveFeedReader(
            ActiveFeedReader delegate, Cache<String, Optional<ActiveFeed>> cache, FeedChangeListener listener) {
        this.delegate = delegate;
        this.cache = cache;
        this.listener = listener;
    }

    @Override
    public Optional<ActiveFeed> find() {
        return cache.get(KEY, key -> load());
    }

    private Optional<ActiveFeed> load() {
        Optional<ActiveFeed> feed = delegate.find();
        long current = feed.map(ActiveFeed::feedVersionId).orElse(NO_FEED);
        long previous = lastFeedVersionId.getAndSet(current);
        if (previous != NO_FEED && current != NO_FEED && previous != current) {
            listener.onFeedChanged(previous, current);
        }
        return feed;
    }
}
