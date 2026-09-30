package dev.pti.analytics.bunching.domain;

import java.util.Locale;

/**
 * How the time a leader passed a stop was found (DOC-23 §5.3). {@link #tag()} is the {@code reason} label of
 * {@code pti_analytics_bunching_evaluations_total} for an evaluated follower.
 */
public enum PassSource {
    /** From the leader's positions on both sides of the stop, or from a position at the stop. */
    OBSERVED,
    /** The leader was first seen past the stop: the passage is estimated from the schedule. */
    ESTIMATED;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
