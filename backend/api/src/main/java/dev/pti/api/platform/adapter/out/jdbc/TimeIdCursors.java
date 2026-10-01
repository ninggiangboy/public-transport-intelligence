package dev.pti.api.platform.adapter.out.jdbc;

import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.ValidationException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The keyset cursor of the lists that sort by a time and then an id ({@code ORDER BY episode_start DESC, id DESC},
 * DOC-31 §5.1): the two keys of the last row as text, and back into the values the SQL binds. The instant keeps the
 * microseconds of the column, so the next page starts exactly after the last row.
 */
public final class TimeIdCursors {

    /** The decoded keys of a cursor: bind them as {@code cursorTs} and {@code cursorId}. */
    public record TimeId(Instant time, UUID id) {}

    private TimeIdCursors() {}

    public static KeysetCursor of(Instant time, UUID id) {
        return KeysetCursor.of(time.toString(), id.toString());
    }

    /**
     * @return the keys, or {@code null} for the first page
     * @throws ValidationException on the field {@code cursor} when the keys are not a time and an id: a cursor is not
     *     signed, so its keys are checked again here
     */
    public static @Nullable TimeId parse(@Nullable KeysetCursor cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.keys().size() != 2) {
                throw new IllegalArgumentException("two keys expected");
            }
            return new TimeId(
                    Instant.parse(cursor.keys().get(0)),
                    UUID.fromString(cursor.keys().get(1)));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ValidationException.of("cursor", "is not a valid cursor");
        }
    }
}
