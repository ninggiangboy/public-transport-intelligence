package dev.pti.api.etlops.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.replay.*} (DOC-22 §8): the rules of a raw zone replay, the same keys {@code etl-batch} reads, so that the
 * API refuses what the job would. {@code max-window} also is a CHECK of the table.
 *
 * @param maxWindow the longest window, 7 days
 * @param rawSettle how long the raw zone needs to hold every object of a window, 10 minutes
 * @param rawMaxAge how far back the raw zone is kept for sure, 29 days
 */
@ConfigurationProperties("pti.replay")
@Validated
public record ReplayProperties(
        @NotNull @DefaultValue("7d") Duration maxWindow,
        @NotNull @DefaultValue("10m") Duration rawSettle,
        @NotNull @DefaultValue("29d") Duration rawMaxAge) {}
