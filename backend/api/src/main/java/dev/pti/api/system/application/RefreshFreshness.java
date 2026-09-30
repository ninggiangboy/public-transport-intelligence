package dev.pti.api.system.application;

import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.system.application.port.FreshnessMetrics;
import dev.pti.api.system.application.port.FreshnessQuery;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.api.system.domain.SourceReading;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run of the freshness probe (DOC-32 E-60): read the newest event of each source, the ACTIVE feed and, every
 * {@code insight-interval} only, the ETA computation time; keep the result for the endpoint, for {@code X-Data-As-Of}
 * and for the gauge. A failed run keeps the previous result with {@code probeError} set and removes the gauge; it
 * never throws, because the scheduler must go on.
 */
public final class RefreshFreshness {

    private static final Logger log = LoggerFactory.getLogger(RefreshFreshness.class);

    private final FreshnessQuery query;
    private final FreshnessSnapshots snapshots;
    private final FreshnessMetrics metrics;
    private final ActiveFeedReader feeds;
    private final BusinessClock clock;
    private final Duration insightInterval;

    public RefreshFreshness(
            FreshnessQuery query,
            FreshnessSnapshots snapshots,
            FreshnessMetrics metrics,
            ActiveFeedReader feeds,
            BusinessClock clock,
            Duration insightInterval) {
        this.query = query;
        this.snapshots = snapshots;
        this.metrics = metrics;
        this.feeds = feeds;
        this.clock = clock;
        this.insightInterval = insightInterval;
    }

    public void execute() {
        Optional<FreshnessSnapshot> previous = snapshots.current();
        try {
            FreshnessSnapshot snapshot = probe(previous.orElse(null));
            snapshots.store(snapshot);
            metrics.publish(snapshot);
        } catch (RuntimeException e) {
            log.warn("Freshness probe failed; keeping the previous result", e);
            previous.ifPresent(old -> snapshots.store(old.withProbeError()));
            metrics.clear();
        }
    }

    private FreshnessSnapshot probe(@Nullable FreshnessSnapshot previous) {
        Instant now = clock.instant();
        SourceReading reading = query.readSources();
        Instant etaReadAt;
        Instant etaComputedAt;
        Instant previousEtaRead = previous != null ? previous.etaReadAt() : null;
        if (previous != null && previousEtaRead != null && now.isBefore(previousEtaRead.plus(insightInterval))) {
            etaReadAt = previousEtaRead;
            etaComputedAt = previous.etaComputedAt();
        } else {
            etaReadAt = now;
            etaComputedAt = query.readEtaComputedAt().orElse(null);
        }
        Optional<ActiveFeed> feed = feeds.find();
        Map<SourceKind, Instant> lastEventAt = new EnumMap<>(SourceKind.class);
        put(lastEventAt, SourceKind.GTFS_RT_VEHICLE_POSITION, reading.vehiclePosition());
        put(lastEventAt, SourceKind.GTFS_RT_TRIP_UPDATE, reading.tripUpdate());
        put(lastEventAt, SourceKind.TICKETING_SALES, reading.ticketSales());
        return new FreshnessSnapshot(
                now, feed.orElse(null), lastEventAt, etaComputedAt, etaReadAt, reading.otpComputedAt(), false);
    }

    private static void put(Map<SourceKind, Instant> target, SourceKind kind, @Nullable Instant value) {
        if (value != null) {
            target.put(kind, value);
        }
    }
}
