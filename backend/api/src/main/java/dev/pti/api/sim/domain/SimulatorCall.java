package dev.pti.api.sim.domain;

import org.jspecify.annotations.Nullable;

/**
 * A request to the simulator, as the proxy forwards it: the method, the simulator's path ({@code /sim/status}), the raw
 * query and body of the caller, and who asks, which the simulator records as {@code requestedBy}.
 */
public record SimulatorCall(
        String method,
        String path,
        @Nullable String query,
        @Nullable String contentType,
        byte[] body,
        String requestedBy) {}
