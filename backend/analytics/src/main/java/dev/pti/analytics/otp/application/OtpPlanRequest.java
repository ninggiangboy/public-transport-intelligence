package dev.pti.analytics.otp.application;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param serviceDates the dates a request asked for; {@code null} or empty for the default of the nightly run
 * @param runDate the local date the nightly run belongs to, which {@code scheduled:<runDate>} carries; {@code null}
 *     for today. The default dates are the ones before it.
 */
public record OtpPlanRequest(
        @Nullable List<LocalDate> serviceDates, @Nullable LocalDate runDate) {}
