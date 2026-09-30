package dev.pti.analytics.otp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The service dates of an OTP run (DOC-23 §8.2, AN-O-05, AN-O-06) and the tolerance band (§8.1). */
class OtpDomainTest {

    private static final LocalDate RUN_DATE = LocalDate.parse("2026-09-30");

    @Test
    void anO05ARunOnThe30thScoresThe29thThe28thAndThe27th() {
        // recompute-days = 2: yesterday and the two days before it, newest first.
        assertThat(OtpServiceDates.defaults(RUN_DATE, 2))
                .containsExactly(
                        LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-27"));
    }

    @Test
    void theNumberOfExtraDaysFollowsTheSetting() {
        assertThat(OtpServiceDates.defaults(RUN_DATE, 1))
                .containsExactly(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-28"));
        assertThat(OtpServiceDates.defaults(RUN_DATE, 5)).hasSize(6);
    }

    @Test
    void requestedDatesAreNewestFirstAndOnceEach() {
        List<LocalDate> dates = OtpServiceDates.normalize(
                List.of(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-27")));

        assertThat(dates).containsExactly(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-27"));
    }

    @Test
    void anO06AFutureDateIsNotScorable() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OtpServiceDates.requireScorable(LocalDate.parse("2026-10-02"), RUN_DATE, null))
                .withMessage("Service date 2026-10-02 is not before today (2026-09-30)");
    }

    @Test
    void todayIsNotScorableEitherBecauseTheDayIsNotOver() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OtpServiceDates.requireScorable(RUN_DATE, RUN_DATE, null))
                .withMessageContaining("is not before today");
        OtpServiceDates.requireScorable(RUN_DATE.minusDays(1), RUN_DATE, null);
    }

    @Test
    void aDateOlderThanTheRetentionOfTripUpdatesIsNotScorable() {
        LocalDate earliest = LocalDate.parse("2026-07-02");

        OtpServiceDates.requireScorable(earliest, RUN_DATE, earliest);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OtpServiceDates.requireScorable(earliest.minusDays(1), RUN_DATE, earliest))
                .withMessage("Service date 2026-07-01 is older than the retention of trip updates (from 2026-07-02)");
    }

    @Test
    void aListIsReadInTheFormsThatJobRunAndTheApiProduce() {
        List<LocalDate> expected = List.of(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-28"));

        assertThat(OtpServiceDates.parse("2026-09-27+2026-09-28")).isEqualTo(expected);
        assertThat(OtpServiceDates.parse("2026-09-27, 2026-09-28")).isEqualTo(expected);
        assertThat(OtpServiceDates.parse("[\"2026-09-27\",\"2026-09-28\"]")).isEqualTo(expected);
        assertThat(OtpServiceDates.parse("2026-09-27")).containsExactly(LocalDate.parse("2026-09-27"));
    }

    @Test
    void aListThatIsEmptyOrNotDatesIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> OtpServiceDates.parse(" "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OtpServiceDates.parse("2026-09-27+yesterday"))
                .withMessage("Service date is not an ISO-8601 date: yesterday");
        assertThatIllegalArgumentException().isThrownBy(() -> OtpServiceDates.normalize(List.of()));
    }

    @Test
    void theBandIsTheSettingsInWholeSeconds() {
        assertThat(OtpTolerances.of(AnalyticsPropertiesFixtures.defaults().otp().toSettings()))
                .isEqualTo(new OtpTolerances(300, 300));
        assertThat(OtpTolerances.of(new OtpSettings(Duration.ofSeconds(60), Duration.ofMinutes(10), 2)))
                .isEqualTo(new OtpTolerances(60, 600));
    }

    @Test
    void aNegativeToleranceIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OtpTolerances(-1, 300));
    }
}
