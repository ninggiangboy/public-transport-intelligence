package dev.pti.api.system.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.observability.freshness-probe.*} (DOC-29 §2): the interval of the probe, 15 s. {@code enabled} turns the
 * scheduler off for tests that feed the probe result themselves.
 */
@ConfigurationProperties("pti.observability.freshness-probe")
@Validated
public record FreshnessProbeProperties(@NotNull Duration interval, boolean enabled) {}
