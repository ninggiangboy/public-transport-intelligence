package dev.pti.api.stream.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A UI event as the hub holds it (DOC-26 §3): the envelope of DOC-33 §2.1 read from {@code pti.events.ui}, plus the
 * order in which it reached this pod. The JSON of its frames is made at most twice, once for viewers and once for
 * anonymous callers, and kept in {@link #frames()} for every connection that sends it.
 *
 * @param seq pod-local arrival order, assigned by the hub; 0 until then
 * @param id the publisher's ULID, which becomes the SSE {@code id:}
 * @param sourceRecordTs record time of the data behind the event, for {@code pti_end_to_end_latency_seconds}
 */
public record HubEvent(
        long seq,
        String id,
        String type,
        UiChannel channel,
        Audience audience,
        Instant occurredAt,
        @Nullable Instant sourceRecordTs,
        @Nullable String routeId,
        Map<String, Object> data,
        FrameCache frames) {

    /** The channels whose events the ring buffer keeps for replay (DOC-26 §4.3); vehicles are refetched instead. */
    public static final Set<UiChannel> REPLAYED_CHANNELS = Set.of(UiChannel.ALERTS, UiChannel.JOBS, UiChannel.DLQ);

    public HubEvent {
        // Not Map.copyOf: JSON nulls are values.
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** A new event as the consumer reads it, without a sequence number yet. */
    public static HubEvent received(
            String id,
            String type,
            UiChannel channel,
            Audience audience,
            Instant occurredAt,
            @Nullable Instant sourceRecordTs,
            @Nullable String routeId,
            Map<String, Object> data) {
        return new HubEvent(
                0, id, type, channel, audience, occurredAt, sourceRecordTs, routeId, data, new FrameCache());
    }

    public HubEvent withSeq(long value) {
        return new HubEvent(value, id, type, channel, audience, occurredAt, sourceRecordTs, routeId, data, frames);
    }

    /** The same envelope with other data and a new cache, as the vehicle throttle makes when it merges events. */
    public HubEvent withData(String newId, Map<String, Object> newData, @Nullable Instant newSourceRecordTs) {
        return new HubEvent(
                seq, newId, type, channel, audience, occurredAt, newSourceRecordTs, routeId, newData, new FrameCache());
    }

    public boolean replayed() {
        return REPLAYED_CHANNELS.contains(channel);
    }
}
