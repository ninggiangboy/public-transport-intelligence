package dev.pti.api.platform.domain;

import org.jspecify.annotations.Nullable;

/**
 * What a keyset-paged use case is asked for (DOC-31 §5.1): at most {@code limit} rows after {@code after}, the sort
 * key of the last row of the previous page ({@code null} for the first page). The web adapter has validated the limit
 * against the configured bounds and decoded the cursor.
 */
public record PageRequest(int limit, @Nullable KeysetCursor after) {

    public PageRequest {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
    }

    public static PageRequest first(int limit) {
        return new PageRequest(limit, null);
    }

    /** How many rows the repository reads: one more than the page, to learn whether another page follows. */
    public int fetchSize() {
        return limit + 1;
    }
}
