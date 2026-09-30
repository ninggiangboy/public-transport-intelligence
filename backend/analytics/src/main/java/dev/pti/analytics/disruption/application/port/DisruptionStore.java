package dev.pti.analytics.disruption.application.port;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.disruption.domain.Arrival;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the disruption detector reads and writes in the warehouse (DOC-23 §6): the arrivals it analyses, its state per
 * route and direction, the hourly snapshots and the episodes. The calls that write run in the transaction of the
 * caller and open none of their own (DOC-49 §5.1). The recompute slice (DOC-23 §11.4) reads and writes the same state
 * through this port.
 */
public interface DisruptionStore {

    /** The oldest {@code last_bucket} of the route's directions: the cursor of the route (DOC-23 §2.2). */
    Optional<Instant> cursor(String routeId);

    /**
     * The newest {@code event_timestamp} of the route's trip updates in the service dates, the input of the watermark
     * (DOC-23 §2.2).
     */
    Optional<Instant> newestUpdate(String routeId, DateRange serviceDates);

    /** The stored state of the route, by direction; a direction that was never processed is absent. */
    Map<Integer, BaselineState> baselines(String routeId);

    /** The episode with this id when it is open; empty when it is closed or does not exist. */
    Optional<DisruptionEpisode> openEpisode(UUID id);

    /**
     * The observed arrivals of the route with {@code observed_at} in {@code [from, to)} (DOC-23 §6.1).
     *
     * @param serviceDates the partitions to read: {@code localDate(from) − 1 .. localDate(to)}
     */
    List<Arrival> arrivals(String routeId, Instant from, Instant to, DateRange serviceDates);

    /** Whether there is any observed arrival in {@code [from, to)}, for the log of skipped grid points. */
    boolean hasArrivals(String routeId, Instant from, Instant to, DateRange serviceDates);

    /** Inserts or replaces the state of one direction. */
    void saveBaseline(String routeId, int directionId, BaselineState state);

    /** Inserts or replaces the snapshot of one direction at an hour (DOC-23 §6.4); a recompute overwrites it. */
    void saveSnapshot(Instant snapshotHour, String routeId, int directionId, BaselineState state);

    /**
     * Upserts the episode by id (DOC-23 §2.4, §6.5). Only the columns the analysis computes are written, never the
     * enrichment of triage.
     *
     * @param batchId the {@code batch_id} of the run
     */
    void saveEpisode(DisruptionEpisode episode, UUID batchId);

    /** Routes that hold state: an open episode or a running count (DOC-23 §4.2). */
    List<String> routesNeedingTick();
}
