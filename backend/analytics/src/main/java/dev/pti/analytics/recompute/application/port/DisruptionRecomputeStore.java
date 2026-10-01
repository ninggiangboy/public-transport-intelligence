package dev.pti.analytics.recompute.application.port;

import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.recompute.domain.StoredEpisode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the disruption recompute reads and deletes beyond {@code DisruptionStore} (DOC-23 §11.4). The calls run in the
 * transaction of the recompute item and open none.
 */
public interface DisruptionRecomputeStore {

    /**
     * The latest snapshot of the direction from which a replay can start: taken at or before {@code latestHour}, with
     * no open episode and no running count of high buckets. Empty when there is none.
     */
    Optional<BaselineState> latestSnapshot(String routeId, int directionId, Instant latestHour);

    /** The stored episodes of the direction that started at or after {@code from}. */
    List<StoredEpisode> episodesStartingFrom(String routeId, int directionId, Instant from);

    /** Deletes the episode row; the enrichment of a reproduced episode is not touched. */
    void deleteEpisode(UUID id);
}
