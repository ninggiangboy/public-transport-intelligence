package dev.pti.api.system.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.api.freshness.*} (DOC-32 E-60, DOC-29 §3.4): when a source is stale, how often the ETA computation time is
 * read and how old a probe result may be before the endpoint answers 503.
 */
@ConfigurationProperties("pti.api.freshness")
@Validated
public record FreshnessProperties(
        @NotNull @Valid StaleAfter staleAfter,
        @NotNull Duration insightInterval,
        @NotNull Duration maxProbeAge) {

    /** {@code pti.api.freshness.stale-after.*}: 120 s for GTFS-realtime (DR-38), 900 s for ticketing. */
    public record StaleAfter(
            @NotNull Duration gtfsRt, @NotNull Duration ticketing) {}
}
