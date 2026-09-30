package dev.pti.analytics.eta.application;

import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what an ETA aggregation of one hour has to do (DOC-23 §7.2): nothing when there is no feed or no new data,
 * else the list of routes. It is the first call of the job's tasklet; the routes are then aggregated one per call by
 * {@link EtaAggregator}, which a recompute calls the same way with a forced plan.
 */
public final class EtaRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(EtaRunPlanner.class);

    private static final Duration NO_FEED_WARNING_INTERVAL = Duration.ofMinutes(1);

    private final EtaAggregateStore store;
    private final AnalyticsReferenceCache reference;
    private final BusinessClock clock;
    private final AtomicReference<@Nullable Instant> lastNoFeedWarning = new AtomicReference<>();

    public EtaRunPlanner(EtaAggregateStore store, AnalyticsReferenceCache reference, BusinessClock clock) {
        this.store = store;
        this.reference = reference;
        this.clock = clock;
    }

    public EtaPlan plan(EtaPlanRequest request) {
        if (!reference.hasActiveFeed()) {
            warnNoFeed();
            return EtaPlan.noFeed();
        }
        ZoneId zone = reference.agencyZone();
        String watermark = store.sourceWatermark(ServiceDates.covering(request.hour(), request.hour(), zone));
        Optional<String> checkpoint = store.checkpoint();
        if (!request.force() && checkpoint.filter(watermark::equals).isPresent()) {
            return EtaPlan.upToDate(watermark);
        }
        return EtaPlan.run(store.routeIds(), watermark);
    }

    /** At most one warning a minute, so that a job that finds no feed does not flood the log (DOC-23 §15). */
    private void warnNoFeed() {
        Instant now = clock.realNow();
        Instant last = lastNoFeedWarning.get();
        if ((last == null || Duration.between(last, now).compareTo(NO_FEED_WARNING_INTERVAL) >= 0)
                && lastNoFeedWarning.compareAndSet(last, now)) {
            log.warn("No feed is ACTIVE: the ETA aggregation has no schedule data and does nothing");
        }
    }
}
