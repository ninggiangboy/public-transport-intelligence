package dev.pti.analytics.alert.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.id.InsightIds;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An alert about to be inserted into {@code ops.alert_event} (DOC-23 §10.1). The id is derived from the dedup key, so
 * running the same analysis again yields the same alert and the insert finds it.
 *
 * @param severity 1, or 2 for a disruption with a high peak z-score
 * @param routeId the route, {@code null} for an anomaly at a sale point that has none
 * @param body JSON-compatible values with camelCase keys
 */
public record AlertDraft(
        UUID id,
        AlertType type,
        int severity,
        Audience audience,
        @Nullable String routeId,
        String refTable,
        String refId,
        String title,
        Map<String, Object> body,
        String dedupKey) {

    public AlertDraft {
        if (severity < 1 || severity > 2) {
            throw new IllegalArgumentException("Alert severity is 1 or 2, not " + severity);
        }
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > AlertTitles.MAX_LENGTH) {
            throw new IllegalArgumentException("An alert title is 1 to " + AlertTitles.MAX_LENGTH + " characters");
        }
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }

    /**
     * The alert of a new episode or anomaly: audience, reference table and dedup key follow from the type.
     *
     * @param refId the id of the episode or anomaly (DOC-23 §2.3)
     */
    public static AlertDraft of(
            AlertType type,
            UUID refId,
            int severity,
            @Nullable String routeId,
            String title,
            Map<String, Object> body) {
        String dedupKey = type.dedupKey(refId);
        return new AlertDraft(
                InsightIds.alert(dedupKey),
                type,
                severity,
                type.audience(),
                routeId,
                type.refTable(),
                refId.toString(),
                title,
                body,
                dedupKey);
    }
}
