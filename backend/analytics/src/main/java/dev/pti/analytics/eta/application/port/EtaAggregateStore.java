package dev.pti.analytics.eta.application.port;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.eta.domain.EtaWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The warehouse side of the ETA aggregation (DOC-23 §7). The aggregation itself is one SQL statement per route: the
 * statistics of tens of thousands of samples are computed where the data is. Every method runs in the transaction of
 * the caller.
 */
public interface EtaAggregateStore {

    /** What one route's merge did, for the log and the recompute statistics (DOC-23 §11.7). */
    record Merge(int upserted, int deleted) {}

    /**
     * The routes to aggregate: the ones of the ACTIVE feed and the ones that still have rows, so that a route that left
     * the feed has its rows removed (DOC-23 §7.1).
     *
     * @return route ids, sorted
     */
    List<String> routeIds();

    /**
     * A fingerprint of the observed arrivals in {@code serviceDates}: their count and the newest observation time. It
     * changes whenever data that can reach the aggregation arrives (§7.2).
     */
    String sourceWatermark(DateRange serviceDates);

    /** The fingerprint the last completed run stored, if any. */
    Optional<String> checkpoint();

    /** Stores the fingerprint of a completed run ({@code etl_checkpoint} key {@code analytics.eta}). */
    void saveCheckpoint(String watermark, Instant watermarkTs, @Nullable Long jobExecutionId);

    /**
     * Recomputes the rows of one route from its samples in the window and merges them (DOC-23 §2.4): a key with
     * samples is upserted, a key of the route without samples is deleted.
     */
    Merge recompute(String routeId, EtaWindow window, Instant computedAt, UUID batchId);
}
