package dev.pti.api.insight.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How days of OTP become the scorecard of a range (DOC-32 E-14, EP-13). */
class OtpScorecardTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-09-21");

    private static OtpDay day(String route, int offset, int onTime, int early, int late, int early300, int late300) {
        int observations = onTime + early + late;
        return new OtpDay(
                route,
                MONDAY.plusDays(offset),
                BigDecimal.valueOf(onTime * 100.0 / observations).setScale(2, java.math.RoundingMode.HALF_UP),
                onTime,
                early,
                late,
                observations,
                10 + offset,
                early300,
                late300);
    }

    @Test
    @DisplayName("EP-13 the percentage is worked out from the summed counters, not as the mean of daily percentages")
    void percentageFromTheSum() {
        // Day 1: 10 of 10 on time (100 %). Day 2: 10 of 1010 on time (0.99 %). The mean of the two would be about 50 %.
        OtpScorecard scorecard = OtpScorecard.of(
                MONDAY,
                MONDAY.plusDays(1),
                List.of(day("18", 0, 10, 0, 0, 300, 300), day("18", 1, 10, 500, 500, 300, 300)));

        RouteOtp route = scorecard.routes().getFirst();

        assertThat(route.onTimeCount()).isEqualTo(20);
        assertThat(route.observationCount()).isEqualTo(1020);
        assertThat(route.otpPercentage()).isEqualByComparingTo("1.96");
        assertThat(route.tripCount()).isEqualTo(10 + 11);
    }

    @Test
    @DisplayName("EP-13 days with other tolerances give mixedTolerances and no tolerance")
    void mixedTolerances() {
        OtpScorecard scorecard = OtpScorecard.of(
                MONDAY,
                MONDAY.plusDays(1),
                List.of(day("18", 0, 90, 5, 5, 300, 300), day("18", 1, 90, 5, 5, 240, 300)));

        RouteOtp route = scorecard.routes().getFirst();

        assertThat(route.mixedTolerances()).isTrue();
        assertThat(route.earlyToleranceSeconds()).isNull();
        assertThat(route.lateToleranceSeconds()).isNull();
        assertThat(route.otpPercentage()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("Days with the same tolerances give them, and the daily series in date order")
    void sameTolerances() {
        OtpScorecard scorecard = OtpScorecard.of(
                MONDAY,
                MONDAY.plusDays(2),
                List.of(day("18", 2, 80, 10, 10, 300, 300), day("18", 0, 90, 5, 5, 300, 300)));

        RouteOtp route = scorecard.routes().getFirst();

        assertThat(route.mixedTolerances()).isFalse();
        assertThat(route.earlyToleranceSeconds()).isEqualTo(300);
        assertThat(route.lateToleranceSeconds()).isEqualTo(300);
        assertThat(route.daily()).extracting(RouteOtp.Day::serviceDate).containsExactly(MONDAY, MONDAY.plusDays(2));
    }

    @Test
    @DisplayName("The worst route comes first, routes with the same percentage by route id")
    void worstFirst() {
        OtpScorecard scorecard = OtpScorecard.of(
                MONDAY,
                MONDAY,
                List.of(
                        day("901", 0, 90, 5, 5, 300, 300),
                        day("18", 0, 50, 25, 25, 300, 300),
                        day("5", 0, 90, 5, 5, 300, 300)));

        assertThat(scorecard.routes()).extracting(RouteOtp::routeId).containsExactly("18", "5", "901");
    }

    @Test
    @DisplayName("No rows is an empty scorecard for the range")
    void empty() {
        OtpScorecard scorecard = OtpScorecard.of(MONDAY, MONDAY.plusDays(6), List.of());

        assertThat(scorecard.routes()).isEmpty();
        assertThat(scorecard.fromDate()).isEqualTo(MONDAY);
        assertThat(scorecard.toDate()).isEqualTo(MONDAY.plusDays(6));
    }
}
