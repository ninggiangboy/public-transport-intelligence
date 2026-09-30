package dev.pti.api.transit.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.api.vehicles.*} (DOC-31 §14, DOC-32 E-05): the most vehicles {@code /vehicles/live} returns, and how old
 * the last position of a vehicle may be before it leaves the map.
 */
@ConfigurationProperties("pti.api.vehicles")
@Validated
public record VehiclesProperties(
        @Min(1) int maxItems, @NotNull Duration maxAge) {}
