package dev.pti.api.stream.domain;

import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.Set;

/** One SSE frame queued for a connection (DOC-33 §2.2, §5.9, §5.10). */
public sealed interface Frame {

    /** {@code retry: 1000}, once at the start of a connection. */
    record Retry(long millis) implements Frame {}

    /**
     * An event; {@code anonymous} picks the public projection. {@code live} is false for events replayed from the
     * buffer, which are not measured for latency (DOC-26 §6.5).
     */
    record Event(HubEvent event, boolean anonymous, boolean live) implements Frame {}

    /** {@code resync}: the client refetches the channels (no {@code id:} line). */
    record Resync(ResyncReason reason, Set<UiChannel> channels, Instant occurredAt) implements Frame {}

    /** {@code heartbeat}: keeps proxies and the client's dead-connection timer happy (no {@code id:} line). */
    record Heartbeat(Instant serverTime, Instant businessNow) implements Frame {}
}
