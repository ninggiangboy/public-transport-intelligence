package dev.pti.analytics.disruption.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Everything the state machine needs to process the next bucket of one (route, direction): the baseline and, when the
 * baseline says one is open, the episode itself. The episode lives in its own table; the baseline row holds only its
 * id.
 *
 * @param episode the open episode; {@code null} exactly when {@code baseline.openEpisodeId()} is
 */
public record DirectionState(
        BaselineState baseline, @Nullable DisruptionEpisode episode) {

    public DirectionState {
        boolean open = baseline.openEpisodeId() != null;
        if (open != (episode != null) || (episode != null && !episode.id().equals(baseline.openEpisodeId()))) {
            throw new IllegalArgumentException("The baseline and the open episode do not refer to each other");
        }
        if (episode != null && !episode.isOpen()) {
            throw new IllegalArgumentException("The open episode of a direction is not closed");
        }
    }

    /** A direction with no history. */
    public static DirectionState initial(Instant lastBucket) {
        return new DirectionState(BaselineState.initial(lastBucket), null);
    }
}
