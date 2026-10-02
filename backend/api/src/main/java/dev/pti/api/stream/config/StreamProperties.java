package dev.pti.api.stream.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.api.sse.*} (DOC-26 §11). A wrong value stops the application at start (DOC-29 §1). */
@ConfigurationProperties("pti.api.sse")
@Validated
public record StreamProperties(
        boolean enabled,
        @NotNull Duration bufferWindow,
        @Min(1) int bufferMaxEvents,
        @Min(1) int connectionQueue,
        @NotNull Duration writeStallTimeout,
        @NotNull Duration heartbeatInterval,
        @Min(1) int vehiclesPerSecond,
        @Min(1) int maxConnections,
        @NotNull Duration maxLifetime,
        @NotNull Duration replayClockSkew,
        @NotNull Duration readyWait,
        @Min(1) int maxRouteFilter) {}
