package dev.pti.api.transit.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.analytics.otp.*} (DOC-23 §13): the on-time window. {@code api} reads the keys {@code analytics} owns, so
 * that {@code /routes/{id}/delays} and OTP agree on what on time means (DOC-32 E-03).
 */
@ConfigurationProperties("pti.analytics.otp")
@Validated
public record OtpProperties(
        @NotNull Duration earlyTolerance, @NotNull Duration lateTolerance) {}
