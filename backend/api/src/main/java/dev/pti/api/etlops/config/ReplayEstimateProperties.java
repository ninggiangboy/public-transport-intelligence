package dev.pti.api.etlops.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.api.replay-estimate.*} (DOC-32 E-53): the messages per second a replay is expected to do. */
@ConfigurationProperties("pti.api.replay-estimate")
@Validated
public record ReplayEstimateProperties(@Min(1) int throughput) {}
