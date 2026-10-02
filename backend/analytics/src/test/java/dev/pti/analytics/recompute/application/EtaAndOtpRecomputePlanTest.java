package dev.pti.analytics.recompute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.support.AnalyticsFakes;
import dev.pti.analytics.support.AnalyticsFakes.Limits;
import dev.pti.analytics.support.AnalyticsFakes.Reference;
import dev.pti.analytics.support.AnalyticsFakes.Transactions;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The plans of the two aggregating detectors (DOC-23 §11.1): one forced ETA run, and one OTP item per date. */
class EtaAndOtpRecomputePlanTest {

    /** 2026-09-30 10:25 in Chicago, a Wednesday. */
    private static final String NOW = "2026-09-30T15:25:00Z";

    private final Reference reference = new Reference();
    private final OtpRecompute otp = new OtpRecompute(mock(OtpScorecardCalculator.class), reference, clock());
    private final EtaRecompute eta = new EtaRecompute(
            mock(EtaRunPlanner.class),
            mock(EtaAggregator.class),
            reference,
            new Transactions(),
            new Limits(new AnalyticsFakes.Journal()),
            clock());

    private static BusinessClock clock() {
        return AnalyticsFakes.clockAt(NOW);
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void etaPlansOneItemForTheCurrentHourWhateverTheRange() {
        assertThat(eta.plan(at("2026-09-01T00:00:00Z"), at("2026-09-02T00:00:00Z")))
                .containsExactly(new WorkItem(
                        Detector.ETA, "2026-09-30T15:00:00Z", at("2026-09-30T15:00:00Z"), at("2026-09-30T15:00:00Z")));
    }

    @Test
    void otpPlansTheLocalDatesOfTheRangeStartingOneDayEarly() {
        // 2026-09-27 12:00Z is Sunday noon in Chicago; the Saturday before it holds the trips that began then.
        List<WorkItem> items = otp.plan(at("2026-09-27T17:00:00Z"), at("2026-09-28T17:00:00Z"));

        assertThat(items).extracting(WorkItem::scope).containsExactly("2026-09-26", "2026-09-27", "2026-09-28");
        assertThat(items).allSatisfy(item -> assertThat(item.detector()).isEqualTo(Detector.OTP));
        assertThat(items.getFirst().from()).as("the start of the local date").isEqualTo(at("2026-09-26T05:00:00Z"));
    }

    @Test
    void otpStopsAtYesterdayBecauseTodayIsNotComplete() {
        List<WorkItem> items = otp.plan(at("2026-09-29T17:00:00Z"), at("2026-09-30T14:00:00Z"));

        assertThat(items).extracting(WorkItem::scope).containsExactly("2026-09-28", "2026-09-29");
    }

    @Test
    void otpScoresOnlyTheDayBeforeForARangeOfToday() {
        assertThat(otp.plan(at("2026-09-30T13:00:00Z"), at("2026-09-30T15:00:00Z")))
                .extracting(WorkItem::scope)
                .containsExactly("2026-09-29");
        assertThat(otp.plan(at("2026-10-02T13:00:00Z"), at("2026-10-02T15:00:00Z")))
                .as("a range in the future")
                .isEmpty();
    }

    @Test
    void withoutAnActiveFeedNeitherPlansAnything() {
        reference.active = false;

        assertThat(eta.plan(at("2026-09-29T00:00:00Z"), at("2026-09-30T00:00:00Z")))
                .isEmpty();
        assertThat(otp.plan(at("2026-09-29T00:00:00Z"), at("2026-09-30T00:00:00Z")))
                .isEmpty();
    }
}
