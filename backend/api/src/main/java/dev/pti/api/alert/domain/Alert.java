package dev.pti.api.alert.domain;

import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code ops.alert_event} (DOC-32 E-20, ADR-0023). {@code audience} is the least visibility the alert
 * has: {@code PUBLIC} shows to everyone, {@code OPERATIONS} and {@code ENGINEERING} only to a signed-in user. {@code
 * body} is the JSON the source wrote, as a map; {@code resolvedAt} is set when the episode closed or Alertmanager said
 * {@code resolved}.
 */
public record Alert(
        UUID id,
        AlertType type,
        int severity,
        Audience audience,
        @Nullable String routeId,
        @Nullable String refTable,
        @Nullable String refId,
        String title,
        Map<String, Object> body,
        Instant createdAt,
        @Nullable String acknowledgedBy,
        @Nullable Instant acknowledgedAt,
        @Nullable Instant resolvedAt) {

    public Alert {
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }

    /** The alert as an anonymous caller may see it: see {@link AlertProjection}. */
    public Alert forAnonymous() {
        return AlertProjection.forAnonymous(this);
    }

    /** {@link AlertLinks#of}: where the screens take the user for this alert. */
    public String link() {
        return AlertLinks.of(this);
    }
}
