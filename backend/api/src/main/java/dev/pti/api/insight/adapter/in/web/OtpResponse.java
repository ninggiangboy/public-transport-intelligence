package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.insight.domain.RouteOtp;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The OTP scorecard (DOC-32 E-14): a small list, whole, worst route first. The tolerances are there when every day of
 * the range used the same; otherwise {@code mixedTolerances} is {@code true} and they are not.
 */
public record OtpResponse(String fromDate, String toDate, List<Item> items) {

    /** One route over the range. */
    public record Item(
            String routeId,
            BigDecimal otpPercentage,
            long onTimeCount,
            long earlyCount,
            long lateCount,
            long observationCount,
            long tripCount,
            @Nullable Integer earlyToleranceSeconds,
            @Nullable Integer lateToleranceSeconds,
            @Nullable Boolean mixedTolerances,
            List<Day> daily) {}

    /** One day of the sparkline of a route. */
    public record Day(String serviceDate, BigDecimal otpPercentage, int observationCount) {}

    static OtpResponse from(OtpScorecard scorecard) {
        return new OtpResponse(
                scorecard.fromDate().toString(),
                scorecard.toDate().toString(),
                scorecard.routes().stream().map(OtpResponse::item).toList());
    }

    private static Item item(RouteOtp route) {
        return new Item(
                route.routeId(),
                route.otpPercentage(),
                route.onTimeCount(),
                route.earlyCount(),
                route.lateCount(),
                route.observationCount(),
                route.tripCount(),
                route.earlyToleranceSeconds(),
                route.lateToleranceSeconds(),
                route.mixedTolerances() ? Boolean.TRUE : null,
                route.daily().stream()
                        .map(day -> new Day(day.serviceDate().toString(), day.otpPercentage(), day.observationCount()))
                        .toList());
    }
}
