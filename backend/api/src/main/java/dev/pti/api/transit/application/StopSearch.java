package dev.pti.api.transit.application;

import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.transit.domain.BoundingBox;
import org.jspecify.annotations.Nullable;

/** What {@code GET /stops} is asked for (DOC-32 E-06): the two modes are different queries with different results. */
public sealed interface StopSearch {

    /**
     * Ranked text search: no paging, at most {@code limit} stops.
     *
     * @param q plain text of at least two characters
     */
    record ByText(String q, int limit) implements StopSearch {

        public ByText {
            if (q.isBlank() || limit < 1) {
                throw new IllegalArgumentException("A text search needs a query and a positive limit");
            }
        }
    }

    /**
     * Stops in a map window and/or on a route, keyset-paged by stop id; at least one of the two filters is set.
     *
     * @param bbox {@code null} for no window
     * @param routeId {@code null} for no route filter
     */
    record ByArea(@Nullable BoundingBox bbox, @Nullable String routeId, PageRequest page) implements StopSearch {

        public ByArea {
            if (bbox == null && routeId == null) {
                throw new IllegalArgumentException("An area search needs a bbox or a route");
            }
        }
    }
}
