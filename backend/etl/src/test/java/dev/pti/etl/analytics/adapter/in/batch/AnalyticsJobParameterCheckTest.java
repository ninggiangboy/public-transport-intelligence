package dev.pti.etl.analytics.adapter.in.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.PtiJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which {@code job_request} parameters of the analytics jobs are refused, and with which message (DOC-23 §15). */
class AnalyticsJobParameterCheckTest {

    // 12:30Z on 30 September is 07:30 CDT, still the 30th in Chicago.
    private final BusinessClock clock =
            new BusinessClock(Clock.fixed(Instant.parse("2026-09-30T12:30:00Z"), ZoneOffset.UTC), Duration.ZERO);
    private final AnalyticsJobParameterCheck check =
            new AnalyticsJobParameterCheck(clock, ZoneId.of("America/Chicago"), Duration.ofDays(90));

    private void eta(String name, String value) {
        check.check(PtiJob.ETA_AGGREGATION, name, value);
    }

    private void otp(String value) {
        check.check(PtiJob.OTP_SCORECARD, "serviceDates", value);
    }

    @Test
    void theHourMayBeAnyWholeHourUpToTheCurrentOne() {
        assertThatCode(() -> eta("hour", "2026-09-30T12:00:00Z")).doesNotThrowAnyException();
        assertThatCode(() -> eta("hour", "2026-01-01T00:00:00Z")).doesNotThrowAnyException();
    }

    @Test
    void anHourAfterTheCurrentOneIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> eta("hour", "2026-09-30T13:00:00Z"))
                .withMessage("Parameter hour 2026-09-30T13:00:00Z is after the current hour 2026-09-30T12:00:00Z");
    }

    @Test
    void anHourThatIsNotOnTheHourOrNotAnInstantIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> eta("hour", "2026-09-30T11:30:00Z"))
                .withMessage("Parameter hour must be on the hour: 2026-09-30T11:30:00Z");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> eta("hour", "yesterday"))
                .withMessage("Parameter hour is not an ISO-8601 instant: yesterday");
    }

    @Test
    void forceIsTrueOrFalse() {
        assertThatCode(() -> eta("force", "true")).doesNotThrowAnyException();
        assertThatCode(() -> eta("force", "false")).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> eta("force", "yes"))
                .withMessage("Parameter force must be true or false: yes");
    }

    @Test
    void anO06EveryServiceDateMustBeBeforeTodayInChicago() {
        assertThatCode(() -> otp("2026-09-29+2026-09-28")).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> otp("2026-09-29+2026-09-30"))
                .withMessage("Service date 2026-09-30 is not before today (2026-09-30)");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> otp("2026-10-02"))
                .withMessage("Service date 2026-10-02 is not before today (2026-09-30)");
    }

    @Test
    void aServiceDateOlderThanTheRetentionOfTripUpdatesIsRefused() {
        LocalDate oldest = LocalDate.parse("2026-09-30").minusDays(90);

        assertThatCode(() -> otp(oldest.toString())).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> otp(oldest.minusDays(1).toString()))
                .withMessageContaining("is older than the retention of trip updates");
    }

    @Test
    void aBadDateListIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> otp("2026-09-29+soon"))
                .withMessage("Service date is not an ISO-8601 date: soon");
    }

    @Test
    void otherJobsAndOtherParametersAreNotChecked() {
        assertThat(PtiJob.values()).contains(PtiJob.GTFS_STATIC_LOAD);
        assertThatCode(() -> check.check(PtiJob.GTFS_STATIC_LOAD, "hour", "noon"))
                .doesNotThrowAnyException();
        assertThatCode(() -> check.check(PtiJob.OTP_SCORECARD, "hour", "noon")).doesNotThrowAnyException();
        assertThatCode(() -> check.check(PtiJob.ETA_AGGREGATION, "serviceDates", "later"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------------------------------ AnalyticsRecomputeJob

    private void recompute(String from, String to) {
        check.checkAll(PtiJob.ANALYTICS_RECOMPUTE, Map.of("fromTs", from, "toTs", to));
    }

    @Test
    void detectorsAreNamesJoinedByPlus() {
        assertThatCode(() -> check.check(PtiJob.ANALYTICS_RECOMPUTE, "detectors", "BUNCHING+DISRUPTION"))
                .doesNotThrowAnyException();
        assertThatCode(() -> check.check(PtiJob.ANALYTICS_RECOMPUTE, "detectors", "OTP"))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> check.check(PtiJob.ANALYTICS_RECOMPUTE, "detectors", "BUNCHING+WEATHER"))
                .withMessageStartingWith("Parameter detectors must be detectors joined by +")
                .withMessageEndingWith(": BUNCHING+WEATHER");
        assertThatIllegalArgumentException().isThrownBy(() -> check.check(PtiJob.ANALYTICS_RECOMPUTE, "detectors", ""));
    }

    @Test
    void theRangeIsTwoInstants() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> check.check(PtiJob.ANALYTICS_RECOMPUTE, "fromTs", "monday"))
                .withMessage("Parameter fromTs is not an ISO-8601 instant: monday");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> check.checkAll(PtiJob.ANALYTICS_RECOMPUTE, Map.of("fromTs", "2026-09-29T00:00:00Z")))
                .withMessage("Parameters fromTs and toTs are required for AnalyticsRecomputeJob");
    }

    @Test
    void anR11TheRangeIsAtMostSevenDaysEndingNoLaterThanNow() {
        assertThatCode(() -> recompute("2026-09-23T12:30:00Z", "2026-09-30T12:30:00Z"))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> recompute("2026-09-22T12:30:00Z", "2026-09-30T12:30:00Z"))
                .withMessage("The range from 2026-09-22T12:30:00Z to 2026-09-30T12:30:00Z is longer than 7 days");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> recompute("2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z"))
                .withMessage("Parameter toTs 2026-09-30T13:00:00Z is after the current time 2026-09-30T12:30:00Z");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> recompute("2026-09-30T12:00:00Z", "2026-09-30T12:00:00Z"))
                .withMessage("Parameter fromTs 2026-09-30T12:00:00Z must be before toTs 2026-09-30T12:00:00Z");
    }

    @Test
    void theRangeRuleOnlyAppliesToTheRecomputeJob() {
        assertThatCode(() -> check.checkAll(PtiJob.OTP_SCORECARD, Map.of())).doesNotThrowAnyException();
    }
}
