package dev.pti.analytics.otp.application;

import dev.pti.analytics.otp.domain.OtpServiceDates;
import dev.pti.analytics.otp.domain.OtpSettings;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which service dates an OTP run scores (DOC-23 §8.2): the ones a request named, else yesterday and the days
 * before it. It is the first call of the job's tasklet; the days are then scored one per call by
 * {@link OtpScorecardCalculator}.
 */
public final class OtpRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(OtpRunPlanner.class);

    private static final Duration NO_FEED_WARNING_INTERVAL = Duration.ofMinutes(1);

    private final AnalyticsReferenceCache reference;
    private final BusinessClock clock;
    private final OtpSettings settings;
    private final AtomicReference<@Nullable Instant> lastNoFeedWarning = new AtomicReference<>();

    public OtpRunPlanner(AnalyticsReferenceCache reference, BusinessClock clock, OtpSettings settings) {
        this.reference = reference;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * @throws IllegalArgumentException when a requested date is not before today
     */
    public OtpPlan plan(OtpPlanRequest request) {
        if (!reference.hasActiveFeed()) {
            warnNoFeed();
            return OtpPlan.noFeed();
        }
        LocalDate today = clock.instant().atZone(reference.agencyZone()).toLocalDate();
        List<LocalDate> requested = request.serviceDates();
        if (requested != null && !requested.isEmpty()) {
            List<LocalDate> dates = OtpServiceDates.normalize(requested);
            dates.forEach(date -> OtpServiceDates.requireScorable(date, today, null));
            return OtpPlan.run(dates);
        }
        LocalDate runDate = request.runDate() == null ? today : request.runDate();
        return OtpPlan.run(OtpServiceDates.defaults(runDate, settings.recomputeDays()));
    }

    /** At most one warning a minute, so that a job that finds no feed does not flood the log (DOC-23 §15). */
    private void warnNoFeed() {
        Instant now = clock.realNow();
        Instant last = lastNoFeedWarning.get();
        if ((last == null || Duration.between(last, now).compareTo(NO_FEED_WARNING_INTERVAL) >= 0)
                && lastNoFeedWarning.compareAndSet(last, now)) {
            log.warn("No feed is ACTIVE: the OTP scorecard has no schedule data and does nothing");
        }
    }
}
