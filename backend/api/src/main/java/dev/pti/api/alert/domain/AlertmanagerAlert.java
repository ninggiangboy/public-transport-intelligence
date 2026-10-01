package dev.pti.api.alert.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.id.InsightIds;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One element of {@code alerts[]} of an Alertmanager webhook (version 4, DOC-32 E-80), and how it becomes a row of
 * {@code ops.alert_event} (DOC-28, ADR-0023). {@code startsAt} is kept as Alertmanager wrote it: it is part of the
 * dedup key, and a {@code resolved} notice repeats it exactly.
 */
public record AlertmanagerAlert(
        Status status,
        String fingerprint,
        String startsAt,
        Map<String, String> labels,
        Map<String, String> annotations,
        @Nullable String generatorUrl) {

    /** The state Alertmanager reports for the alert. */
    public enum Status {
        FIRING,
        RESOLVED
    }

    /** The longest title the column holds (DOC-15). */
    static final int MAX_TITLE = 200;

    private static final Set<String> DLQ_ALERTS = Set.of("DlqSevereRecords", "DlqUpstreamErrorBurst");

    public AlertmanagerAlert {
        labels = Map.copyOf(labels);
        annotations = Map.copyOf(annotations);
    }

    /** {@code am:<fingerprint>:<startsAt>}: the same alert firing again, or resolving, has the same key. */
    public String dedupKey() {
        return "am:" + fingerprint + ":" + startsAt;
    }

    public String alertName() {
        return labels.getOrDefault("alertname", "unknown");
    }

    /**
     * The row to insert for a firing alert: type by {@code alertname}, severity by the {@code severity} label, audience
     * {@code ENGINEERING} (pipeline alerts are not for passengers), the route from the label {@code route_id}.
     */
    public NewAlert toNewAlert() {
        String dedupKey = dedupKey();
        return new NewAlert(
                InsightIds.alert(dedupKey),
                type(),
                severity(),
                Audience.ENGINEERING,
                labels.get("route_id"),
                title(),
                body(),
                dedupKey);
    }

    /**
     * {@code GtfsRtFeedStale} is {@code FEED_STALE}; the two rules for severe dead letters ({@code DlqSevereRecords}
     * and {@code DlqUpstreamErrorBurst}, FR-09.8) are {@code DLQ_SEVERE}; everything else, {@code DlqNeedsAttention}
     * included, is {@code INFRA}.
     */
    AlertType type() {
        String name = alertName();
        if (name.equals("GtfsRtFeedStale")) {
            return AlertType.FEED_STALE;
        }
        return DLQ_ALERTS.contains(name) ? AlertType.DLQ_SEVERE : AlertType.INFRA;
    }

    /** {@code critical} is 2, {@code warning} 1, {@code info} 0; a label that is missing or unknown counts as a warning. */
    int severity() {
        return switch (labels.getOrDefault("severity", "warning")) {
            case "critical" -> 2;
            case "info" -> 0;
            default -> 1;
        };
    }

    /** The annotation {@code summary} cut to the 200 characters of the column, or the alert name without one. */
    String title() {
        String summary = annotations.get("summary");
        if (summary == null || summary.isBlank()) {
            return alertName();
        }
        String text = summary.strip();
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE);
    }

    Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("alertname", alertName());
        body.put("labels", labels);
        body.put("annotations", annotations);
        body.put("startsAt", startsAt);
        if (generatorUrl != null && !generatorUrl.isBlank()) {
            body.put("generatorURL", generatorUrl);
        }
        return body;
    }
}
