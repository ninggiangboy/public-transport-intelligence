package dev.pti.api.transit.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.analytics.eta.*} (DOC-23 §13): the keys of the ETA feature that {@code api} reads for the delay profile
 * and the stop arrivals (DOC-32 E-04, E-08).
 */
@ConfigurationProperties("pti.analytics.eta")
@Validated
public record EtaProperties(
        @NotNull @Valid Confidence confidence,
        boolean realtimeEnabled,
        @NotNull Duration realtimeMaxAge,
        @NotNull @Valid Arrivals arrivals) {

    /** {@code pti.analytics.eta.confidence.*} (DOC-23 §7.3). */
    public record Confidence(@Min(1) int mediumMin, @Min(2) int highMin) {}

    /** {@code pti.analytics.eta.arrivals.*}: the defaults of the {@code limit} and {@code horizon} parameters. */
    public record Arrivals(
            @Min(1) @Max(30) int defaultLimit, @NotNull Duration horizon) {}
}
