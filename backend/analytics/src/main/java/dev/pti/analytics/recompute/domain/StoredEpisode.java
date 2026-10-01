package dev.pti.analytics.recompute.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The part of a stored episode that the recompute needs to decide what to keep: its id, when it began and when it
 * ended. A bunching or disruption row, whichever table it came from.
 *
 * @param end {@code null} while the episode is open
 */
public record StoredEpisode(
        UUID id, Instant start, @Nullable Instant end) {

    public boolean isOpen() {
        return end == null;
    }
}
