package dev.pti.api.platform.application.port;

/**
 * Told when the ACTIVE feed changes its {@code feedVersionId}, which the 30-second refresh of the active feed notices
 * (DOC-31 §10.3): the GTFS caches of the old feed are cleared.
 */
public interface FeedChangeListener {

    void onFeedChanged(long previousFeedVersionId, long currentFeedVersionId);
}
