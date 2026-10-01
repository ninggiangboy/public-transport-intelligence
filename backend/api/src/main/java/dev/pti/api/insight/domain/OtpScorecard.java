package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The scorecard of a range of service days, worst route first (DOC-32 E-14). */
public record OtpScorecard(LocalDate fromDate, LocalDate toDate, List<RouteOtp> routes) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public OtpScorecard {
        routes = List.copyOf(routes);
    }

    /**
     * Sums the daily counters of each route and divides once: {@code sum(on_time) * 100 / sum(observations)}, rounded
     * to two decimals. Routes are sorted by percentage ascending, then by route id.
     *
     * @param days the rows of the range, in any order
     */
    public static OtpScorecard of(LocalDate fromDate, LocalDate toDate, List<OtpDay> days) {
        Map<String, List<OtpDay>> byRoute = new LinkedHashMap<>();
        days.stream()
                .sorted(Comparator.comparing(OtpDay::routeId).thenComparing(OtpDay::serviceDate))
                .forEach(day -> byRoute.computeIfAbsent(day.routeId(), route -> new ArrayList<>())
                        .add(day));
        List<RouteOtp> routes = byRoute.entrySet().stream()
                .map(entry -> route(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(RouteOtp::otpPercentage).thenComparing(RouteOtp::routeId))
                .toList();
        return new OtpScorecard(fromDate, toDate, routes);
    }

    private static RouteOtp route(String routeId, List<OtpDay> days) {
        long onTime = 0;
        long early = 0;
        long late = 0;
        long observations = 0;
        long trips = 0;
        for (OtpDay day : days) {
            onTime += day.onTimeCount();
            early += day.earlyCount();
            late += day.lateCount();
            observations += day.observationCount();
            trips += day.tripCount();
        }
        OtpDay first = days.getFirst();
        boolean mixed = days.stream()
                .anyMatch(day -> day.earlyToleranceSeconds() != first.earlyToleranceSeconds()
                        || day.lateToleranceSeconds() != first.lateToleranceSeconds());
        BigDecimal percentage = observations == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(onTime)
                        .multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(observations), 2, RoundingMode.HALF_UP);
        return new RouteOtp(
                routeId,
                percentage,
                onTime,
                early,
                late,
                observations,
                trips,
                mixed ? null : first.earlyToleranceSeconds(),
                mixed ? null : first.lateToleranceSeconds(),
                mixed,
                days.stream()
                        .map(day -> new RouteOtp.Day(day.serviceDate(), day.otpPercentage(), day.observationCount()))
                        .toList());
    }
}
