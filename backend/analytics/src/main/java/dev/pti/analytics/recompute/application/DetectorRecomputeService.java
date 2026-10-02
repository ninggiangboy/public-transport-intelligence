package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.port.RecomputeMetrics;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.analytics.recompute.domain.WorkItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AnalyticsRecomputeService} over the per-detector recomputes (DOC-23 §11.1): it chooses the detectors for a
 * replay source, concatenates their plans in the order of {@link Detector}, and counts the rows of every item that ran.
 * A detector without a recompute is left out of a plan; its items cannot be executed.
 */
public final class DetectorRecomputeService implements AnalyticsRecomputeService {

    private final Map<Detector, DetectorRecompute> recomputes = new EnumMap<>(Detector.class);
    private final RecomputeMetrics metrics;

    public DetectorRecomputeService(Collection<? extends DetectorRecompute> recomputes, RecomputeMetrics metrics) {
        recomputes.forEach(recompute -> this.recomputes.put(recompute.detector(), recompute));
        this.metrics = metrics;
    }

    @Override
    public List<WorkItem> plan(ReplaySource source, ReplayRange range) {
        List<WorkItem> items = new ArrayList<>();
        for (Detector detector : detectorsOf(source)) {
            DetectorRecompute recompute = recomputes.get(detector);
            if (recompute != null) {
                items.addAll(recompute.planReplay(range));
            }
        }
        return items;
    }

    @Override
    public List<WorkItem> plan(Set<Detector> detectors, Instant from, Instant to) {
        List<WorkItem> items = new ArrayList<>();
        for (Detector detector : Detector.values()) {
            DetectorRecompute recompute = recomputes.get(detector);
            if (detectors.contains(detector) && recompute != null) {
                items.addAll(recompute.plan(from, to));
            }
        }
        return items;
    }

    @Override
    public DetectorStats execute(WorkItem item, UUID batchId) {
        DetectorRecompute recompute = recomputes.get(item.detector());
        if (recompute == null) {
            throw new IllegalArgumentException("There is no recompute for " + item.detector());
        }
        DetectorStats stats = recompute.execute(item, batchId);
        metrics.upserted(item.detector(), stats.upserted());
        metrics.deleted(item.detector(), stats.deleted());
        return stats;
    }

    /** DOC-23 §11.1, the table of sources: which detectors read what a replay of the source wrote. */
    static List<Detector> detectorsOf(ReplaySource source) {
        return switch (source) {
            case GTFS_RT_VEHICLE_POSITION -> List.of(Detector.BUNCHING);
            case GTFS_RT_TRIP_UPDATE -> List.of(Detector.DISRUPTION, Detector.ETA, Detector.OTP);
            case TICKETING_SALES -> List.of(Detector.TICKETING);
            case TICKETING_SALE_POINTS, GTFS_STATIC -> List.of();
        };
    }
}
