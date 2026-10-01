package dev.pti.api.sim.domain;

import org.jspecify.annotations.Nullable;

/** What the simulator answered: its status, content type and body, which the proxy returns as they are, and {@code Location}. */
public record SimulatorReply(
        int status,
        @Nullable String contentType,
        byte[] body,
        @Nullable String location) {

    public SimulatorReply withLocation(@Nullable String newLocation) {
        return new SimulatorReply(status, contentType, body, newLocation);
    }
}
