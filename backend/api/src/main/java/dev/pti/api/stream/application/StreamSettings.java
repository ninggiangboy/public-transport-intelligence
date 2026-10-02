package dev.pti.api.stream.application;

import java.time.Duration;

/**
 * The {@code pti.api.sse.*} values the hub works with (DOC-26 §11) and the connection caps of DOC-31 §11.
 *
 * @param capsEnabled whether the per-IP and per-user caps apply ({@code pti.api.rate-limit.enabled})
 */
public record StreamSettings(
        Duration bufferWindow,
        int bufferMaxEvents,
        int connectionQueue,
        Duration writeStallTimeout,
        int vehiclesPerSecond,
        int maxConnections,
        Duration replayClockSkew,
        Duration readyWait,
        int maxRouteFilter,
        boolean capsEnabled,
        int perIp,
        int perUser) {}
