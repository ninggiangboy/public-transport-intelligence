package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of the OTP scorecard: a route on a service day, with the tolerances the day was computed with. */
public record OtpDay(
        String routeId,
        LocalDate serviceDate,
        BigDecimal otpPercentage,
        int onTimeCount,
        int earlyCount,
        int lateCount,
        int observationCount,
        int tripCount,
        int earlyToleranceSeconds,
        int lateToleranceSeconds) {}
