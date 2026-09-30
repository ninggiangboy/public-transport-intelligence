package dev.pti.etl.analytics.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.etl.core.EtlSource;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A micro-batch that has committed, as analytics sees it (DOC-23 §4.1).
 *
 * @param batchId the {@code batch_id} of the micro-batch, logged as {@code source_batch_id}
 * @param routeIds the routes the micro-batch wrote rows for
 * @param minEventTs smallest event time written; {@code null} when nothing was written
 * @param minRecordTs smallest Kafka record time (DR-57)
 * @param committedAt real time of the commit
 */
public record BatchCommit(
        UUID batchId,
        EtlSource source,
        Set<String> routeIds,
        @Nullable Instant minEventTs,
        @Nullable Instant minRecordTs,
        Instant committedAt) {

    public BatchCommit {
        routeIds = Set.copyOf(routeIds);
    }

    /**
     * The detector that a micro-batch of this source triggers (DOC-23 §4.1): vehicle positions trigger bunching, trip
     * updates trigger disruption, ticketing triggers nothing because it runs as a job.
     */
    public Optional<Detector> triggeredDetector() {
        return switch (source) {
            case GTFS_RT_VEHICLE_POSITION -> Optional.of(Detector.BUNCHING);
            case GTFS_RT_TRIP_UPDATE -> Optional.of(Detector.DISRUPTION);
            case TICKETING_SALES, TICKETING_SALE_POINTS, GTFS_STATIC -> Optional.empty();
        };
    }
}
