package dev.pti.api.stream.adapter.in.sse;

import java.time.Duration;

/**
 * What {@code GET /stream} checks before the hub sees the request (DOC-26 §7, §11).
 *
 * @param enabled {@code pti.api.sse.enabled}; off, the endpoint answers 503
 * @param maxLifetime {@code pti.api.sse.max-lifetime}, the emitter's timeout
 * @param maxRouteFilter {@code pti.api.sse.max-route-filter}
 */
public record StreamEndpointSettings(boolean enabled, Duration maxLifetime, int maxRouteFilter) {}
