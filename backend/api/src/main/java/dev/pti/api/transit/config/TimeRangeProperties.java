package dev.pti.api.transit.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.api.time.max-range} (DOC-31 §4.3): the longest range of {@code from} and {@code to}. */
@ConfigurationProperties("pti.api.time")
@Validated
public record TimeRangeProperties(@NotNull Duration maxRange) {}
