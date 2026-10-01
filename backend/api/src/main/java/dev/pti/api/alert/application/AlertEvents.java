package dev.pti.api.alert.application;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.domain.ApiTime;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The UI events the API makes for an alert it wrote (DOC-33 §3, §5.5): {@code alert.created} for a row it inserted,
 * {@code alert.updated} for one it acknowledged or resolved. The data is the whole alert as {@code GET /alerts} gives
 * it to a viewer, link included, so that the screen replaces its record of the alert without merging; the audience of
 * the row decides who receives it (the SSE hub projects it for anonymous callers).
 */
final class AlertEvents {

    static final String CREATED = "alert.created";
    static final String UPDATED = "alert.updated";

    private AlertEvents() {}

    static UiEvent created(Alert alert, Instant occurredAt) {
        return event(CREATED, alert, occurredAt);
    }

    static UiEvent updated(Alert alert, Instant occurredAt) {
        return event(UPDATED, alert, occurredAt);
    }

    private static UiEvent event(String type, Alert alert, Instant occurredAt) {
        return UiEvent.of(
                occurredAt,
                type,
                UiChannel.ALERTS,
                alert.audience(),
                alert.id().toString(),
                alert.routeId(),
                null,
                data(alert));
    }

    static Map<String, Object> data(Alert alert) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", alert.id().toString());
        data.put("type", alert.type().name());
        data.put("severity", alert.severity());
        data.put("audience", alert.audience().name());
        put(data, "routeId", alert.routeId());
        put(data, "refTable", alert.refTable());
        put(data, "refId", alert.refId());
        data.put("title", alert.title());
        data.put("body", alert.body());
        data.put("createdAt", ApiTime.format(alert.createdAt()));
        put(data, "acknowledgedBy", alert.acknowledgedBy());
        put(data, "acknowledgedAt", ApiTime.formatNullable(alert.acknowledgedAt()));
        put(data, "resolvedAt", ApiTime.formatNullable(alert.resolvedAt()));
        data.put("link", alert.link());
        return data;
    }

    private static void put(Map<String, Object> data, String key, @Nullable String value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
