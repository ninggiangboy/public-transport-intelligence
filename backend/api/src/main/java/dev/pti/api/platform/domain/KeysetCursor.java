package dev.pti.api.platform.domain;

import java.util.List;

/**
 * The sort key of the last row of a page (DOC-31 §5.1), as strings. A feature turns its own columns into strings when
 * it builds the cursor and parses them back in its adapter; the cursor's wire form (base64url JSON, filter hash) is
 * the web adapter's business, so use cases never see it.
 */
public record KeysetCursor(List<String> keys) {

    public KeysetCursor {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("A cursor needs at least one key");
        }
        keys = List.copyOf(keys);
    }

    public static KeysetCursor of(String... keys) {
        return new KeysetCursor(List.of(keys));
    }
}
