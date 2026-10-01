package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.otp.application.OtpDayRun;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OTP recompute (DOC-23 §11.1): one item per service date, each scored by the nightly calculator. The dates are the
 * local dates of the range, one more at the start because a trip is filed under the day it starts, up to yesterday,
 * the last day that is complete.
 */
public final class OtpRecompute implements DetectorRecompute {

    private final OtpScorecardCalculator calculator;
    private final AnalyticsReferenceCache reference;
    private final BusinessClock clock;

    public OtpRecompute(OtpScorecardCalculator calculator, AnalyticsReferenceCache reference, BusinessClock clock) {
        this.calculator = calculator;
        this.reference = reference;
        this.clock = clock;
    }

    @Override
    public Detector detector() {
        return Detector.OTP;
    }

    @Override
    public List<WorkItem> plan(Instant from, Instant to) {
        if (!reference.hasActiveFeed()) {
            return List.of();
        }
        ZoneId zone = reference.agencyZone();
        LocalDate yesterday = clock.instant().atZone(zone).toLocalDate().minusDays(1);
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate toDate = to.atZone(zone).toLocalDate();
        LocalDate last = toDate.isBefore(yesterday) ? toDate : yesterday;
        List<WorkItem> items = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            Instant start = date.atStartOfDay(zone).toInstant();
            items.add(new WorkItem(Detector.OTP, date.toString(), start, start));
        }
        return items;
    }

    @Override
    public DetectorStats execute(WorkItem item, UUID batchId) {
        return RunStats.of(
                calculator.calculate(new OtpDayRun(LocalDate.parse(item.scope()), batchId, Trigger.RECOMPUTE)));
    }
}
