package dev.pti.api.alert.adapter.in.web;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.domain.ApiTime;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An alert (DOC-32 E-20, E-21). {@code link} is the screen of the UI it belongs to, worked out on reading by the
 * same function as for the UI events. For an anonymous caller the acknowledgement is absent and {@code body} holds
 * only the keys allowed for the type.
 */
public record AlertResponse(
        UUID id,
        String type,
        int severity,
        String audience,
        @Nullable String routeId,
        @Nullable String refTable,
        @Nullable String refId,
        String title,
        Map<String, Object> body,
        String createdAt,
        @Nullable String acknowledgedBy,
        @Nullable String acknowledgedAt,
        @Nullable String resolvedAt,
        String link) {

    static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.id(),
                alert.type().name(),
                alert.severity(),
                alert.audience().name(),
                alert.routeId(),
                alert.refTable(),
                alert.refId(),
                alert.title(),
                alert.body(),
                ApiTime.format(alert.createdAt()),
                alert.acknowledgedBy(),
                ApiTime.formatNullable(alert.acknowledgedAt()),
                ApiTime.formatNullable(alert.resolvedAt()),
                alert.link());
    }
}
