package dev.pti.simulator.feed;

import org.jspecify.annotations.Nullable;

/** A route of the feed. {@code route_type} 0 is light rail, everything else is a bus route (DR-01). */
public record Route(
        String id, @Nullable String shortName, @Nullable String longName, int type) {

    public boolean isRail() {
        return type == 0;
    }

    /** {@code route_short_name}, else {@code route_long_name}, else {@code route_id} (DOC-13 §2.4). */
    public String displayName() {
        if (shortName != null) {
            return shortName;
        }
        return longName != null ? longName : id;
    }
}
