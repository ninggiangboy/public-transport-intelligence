package dev.pti.analytics.alert.domain;

import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ops.alert_event} as a statement of DOC-23 §10.2 returns it. The {@code audience} is the one in the
 * row now: triage may have narrowed it, and an event about the alert must not show more than that (DOC-23 §10.3).
 *
 * @param body the body after the statement, including the keys that triage added
 * @param createdAt database time ({@code now()}), real time
 * @param resolvedAt set once the episode has closed
 */
public record AlertRecord(
        UUID id,
        AlertType type,
        int severity,
        Audience audience,
        @Nullable String routeId,
        String refTable,
        String refId,
        String title,
        Map<String, Object> body,
        Instant createdAt,
        @Nullable Instant resolvedAt) {

    public AlertRecord {
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }
}
