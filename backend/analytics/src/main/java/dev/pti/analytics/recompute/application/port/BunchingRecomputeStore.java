package dev.pti.analytics.recompute.application.port;

import dev.pti.analytics.recompute.domain.StoredEpisode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the bunching recompute reads and deletes beyond the ports of the detector (DOC-23 §11.3). The calls run in the
 * transaction of the recompute item and open none.
 */
public interface BunchingRecomputeStore {

    /**
     * The stored episodes of the route that began before {@code point} and are open or end at or after it: the ones a
     * recompute that starts at {@code point} would cut in half.
     */
    List<StoredEpisode> episodesReaching(String routeId, Instant point);

    /** The stored episodes of the route with {@code from ≤ episode_start ≤ to}: the scope a merge may delete from. */
    List<StoredEpisode> episodesStartingBetween(String routeId, Instant from, Instant to);

    /** Deletes the episode row. The dispatch suggestion and the enrichment of a reproduced episode are not touched. */
    void deleteEpisode(UUID id);

    /**
     * When the oldest pair that the live detector is still counting began ({@code first_below_at}). A recompute may
     * not stop before it has passed that point, because the live state is still to be handed over.
     */
    Optional<Instant> earliestPendingPair(String routeId);

    /**
     * How long the stored episodes that cover {@code point} ({@code episode_start ≤ point ≤ episode_end}) last.
     * Empty when none covers it.
     */
    Optional<Coverage> coverageAt(String routeId, Instant point);

    /**
     * @param open true when one of the covering episodes is still open
     * @param latestEnd the latest end of the covering episodes that are closed; {@code null} when all are open
     */
    record Coverage(boolean open, @Nullable Instant latestEnd) {}
}
