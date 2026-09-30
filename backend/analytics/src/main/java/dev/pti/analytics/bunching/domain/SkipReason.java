package dev.pti.analytics.bunching.domain;

import java.util.Locale;

/**
 * Why a follower was not evaluated at a grid point (DOC-23 §5.4). {@link #tag()} is the {@code reason} label of
 * {@code pti_analytics_bunching_evaluations_total} with {@code result="skipped"}.
 */
public enum SkipReason {
    /** The trip is not in the ACTIVE feed, or the stop sequence is not in its pattern. */
    UNKNOWN_TRIP,
    /** The next stop is one of the first stops of the trip. */
    FIRST_STOPS,
    /** The next stop is one of the last stops of the trip. */
    LAST_STOPS,
    /** The feed has no scheduled headway for the hour. */
    NO_HEADWAY,
    /** The scheduled headway is longer than {@code max-headway}. */
    HEADWAY_TOO_LONG,
    /** No vehicle of the same direction has passed the follower's next stop lately. */
    NO_LEADER;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
