package dev.pti.analytics.bunching.domain;

import java.util.Locale;

/**
 * Why a bunching episode closed ({@code close_reason} of {@code insight_bus_bunching}, DOC-23 §5.5). {@link #tag()} is
 * the {@code reason} label of {@code pti_analytics_episodes_total}.
 */
public enum CloseReason {
    /** The gap grew above {@code close-ratio × headway}. */
    GAP_RECOVERED,
    /** The vehicles are no longer a pair: another vehicle took the leader's place, a trip changed, or they swapped. */
    PAIR_CHANGED,
    /** One of the two vehicles stopped reporting positions. */
    SIGNAL_LOST,
    /** The follower left the evaluated zone: first or last stops, no headway, or a trip the feed does not know. */
    OUT_OF_ZONE;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
