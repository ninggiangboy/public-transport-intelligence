package dev.pti.analytics.event.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A UI event produced by analytics (DOC-23 §3). The envelope of DOC-09 §6 (ULID id, {@code occurred_at}) is added by
 * the sink when it publishes.
 *
 * <p>DOC-49 §11.1 places this type in {@code application.port}, but {@code RunResult} in {@code core.domain} carries
 * it and the layering rule A-11 keeps {@code domain} from depending on {@code application}; it lives in
 * {@code event.domain} instead.
 *
 * @param type event type such as {@code bunching.opened} or {@code alert.created} (DOC-33 §3)
 * @param channel the channel a client subscribes to; {@code ALERTS} for everything analytics produces
 * @param audience who may see the event
 * @param key the Kafka key: the episode id or the alert id
 * @param routeId the route the event is about, when it is about one
 * @param sourceRecordTs the smallest Kafka record time of the micro-batch that caused the event; {@code null} for
 *     ticks, jobs and recomputes (DR-57)
 * @param committedAt real time the causing micro-batch committed, for {@code pti_ui_commit_to_publish_seconds};
 *     {@code null} when no micro-batch caused the event
 * @param data the payload, camelCase keys (DOC-33 §5), JSON-compatible values
 */
public record InsightEvent(
        String type,
        UiChannel channel,
        Audience audience,
        String key,
        @Nullable String routeId,
        @Nullable Instant sourceRecordTs,
        @Nullable Instant committedAt,
        Map<String, Object> data) {

    public InsightEvent {
        if (type == null || type.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("An insight event needs a type and a key");
        }
        if (channel == null || audience == null || data == null) {
            throw new IllegalArgumentException("An insight event needs a channel, an audience and data");
        }
        // Not Map.copyOf: a payload may hold a null value.
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
