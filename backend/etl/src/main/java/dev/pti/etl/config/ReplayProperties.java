package dev.pti.etl.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.replay.*} (DOC-22 §8). */
@ConfigurationProperties("pti.replay")
@Validated
public record ReplayProperties(
        @NotNull Duration rawSettle,
        @NotNull Duration rawMaxAge,
        @NotNull Duration maxWindow,
        @Min(1) int maxObjects,
        @Min(1) int chunkSize,
        @NotNull @Valid Poller poller) {

    public record Poller(@NotNull Duration interval, @Min(1) int maxClaims) {}
}
