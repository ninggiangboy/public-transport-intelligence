package dev.pti.analytics.alert.domain;

import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code alert.created} and {@code alert.updated} events (DOC-23 §10.3, DOC-33 §5.5). Both carry the whole alert
 * as it is after the change, so a client replaces its copy instead of merging fields, and both are addressed to the
 * audience the row has now.
 */
public final class AlertEvents {

    public static final String CREATED = "alert.created";
    public static final String UPDATED = "alert.updated";

    private AlertEvents() {}

    /** The event for a row that the insert returned, which means the alert is new. */
    public static InsightEvent created(
            AlertRecord alert, @Nullable Instant sourceRecordTs, @Nullable Instant committedAt) {
        return event(CREATED, alert, sourceRecordTs, committedAt);
    }

    /** The event for a row that a close or a severity raise returned. */
    public static InsightEvent updated(
            AlertRecord alert, @Nullable Instant sourceRecordTs, @Nullable Instant committedAt) {
        return event(UPDATED, alert, sourceRecordTs, committedAt);
    }

    private static InsightEvent event(
            String type, AlertRecord alert, @Nullable Instant sourceRecordTs, @Nullable Instant committedAt) {
        return new InsightEvent(
                type,
                UiChannel.ALERTS,
                alert.audience(),
                alert.id().toString(),
                alert.routeId(),
                sourceRecordTs,
                committedAt,
                data(alert));
    }

    /** The payload of DOC-33 §5.5 without {@code link}, which the API adds when it projects the event to SSE. */
    static Map<String, Object> data(AlertRecord alert) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", alert.id().toString());
        data.put("type", alert.type().name());
        data.put("severity", alert.severity());
        data.put("audience", alert.audience().name());
        data.put("routeId", alert.routeId());
        data.put("refTable", alert.refTable());
        data.put("refId", alert.refId());
        data.put("title", alert.title());
        data.put("body", alert.body());
        data.put("createdAt", alert.createdAt());
        if (alert.resolvedAt() != null) {
            data.put("resolvedAt", alert.resolvedAt());
        }
        return data;
    }
}
