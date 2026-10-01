package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The on-time performance of a route over a range of days (DOC-32 E-14). The percentage is computed from the summed
 * counters, never as the mean of daily percentages. The tolerances are present only when every day used the same ones;
 * otherwise {@code mixedTolerances} says so (FR-08.2).
 */
public record RouteOtp(
        String routeId,
        BigDecimal otpPercentage,
        long onTimeCount,
        long earlyCount,
        long lateCount,
        long observationCount,
        long tripCount,
        @Nullable Integer earlyToleranceSeconds,
        @Nullable Integer lateToleranceSeconds,
        boolean mixedTolerances,
        List<Day> daily) {

    public RouteOtp {
        daily = List.copyOf(daily);
    }

    /** One day of the sparkline. */
    public record Day(LocalDate serviceDate, BigDecimal otpPercentage, int observationCount) {}
}
