package dev.pti.api.stream.domain;

import org.jspecify.annotations.Nullable;

/**
 * The serialized {@code data:} lines of one event (DOC-26 §3): one for viewers, one for anonymous callers, made by the
 * first connection that sends the event and reused by the others. A race makes the same text twice, which is harmless.
 */
public final class FrameCache {

    private volatile @Nullable String full;
    private volatile @Nullable String publicView;

    public @Nullable String get(boolean anonymous) {
        return anonymous ? publicView : full;
    }

    public void put(boolean anonymous, String json) {
        if (anonymous) {
            publicView = json;
        } else {
            full = json;
        }
    }
}
