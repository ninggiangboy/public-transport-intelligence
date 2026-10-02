package dev.pti.api.stream.domain;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertLinks;
import dev.pti.api.alert.domain.AlertType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a client receives in the {@code data} of an event (DOC-33 §4, §5.5): the whole payload for viewers, the public
 * projection for anonymous callers, and the {@code link} of an alert for both, worked out by the same function as
 * {@code GET /alerts}. The projection is a static allow list per type; a {@code PUBLIC} type that is not in it is not
 * sent to anonymous callers at all, the safe default.
 */
public final class SseProjection {

    /** Keys of {@code data} an anonymous caller keeps, per event type; {@code null} means all of them. */
    private static final Map<String, @Nullable List<String>> PUBLIC_KEYS = publicKeys();

    private static final List<String> ALERT_TYPES = List.of("alert.created", "alert.updated");

    private SseProjection() {}

    private static Map<String, @Nullable List<String>> publicKeys() {
        Map<String, @Nullable List<String>> keys = new LinkedHashMap<>();
        keys.put("vehicles.batch", null);
        List<String> disruption = List.of(
                "id",
                "routeId",
                "directionId",
                "episodeStart",
                "episodeEnd",
                "currentAvgDelaySeconds",
                "affectedStopIds",
                "closeReason");
        keys.put("disruption.opened", disruption);
        keys.put("disruption.closed", disruption);
        keys.put("alert.retracted", List.of("id", "routeId"));
        return keys;
    }

    /** Whether anonymous callers can receive the type at all. */
    public static boolean hasPublicView(String type) {
        return PUBLIC_KEYS.containsKey(type) || ALERT_TYPES.contains(type);
    }

    /** The data a viewer receives: the payload, with the link of an alert added when the publisher left it out. */
    public static Map<String, Object> forViewer(String type, Map<String, Object> data) {
        if (!ALERT_TYPES.contains(type) || data.containsKey("link")) {
            return data;
        }
        Map<String, Object> copy = new LinkedHashMap<>(data);
        link(data).ifPresent(link -> copy.put("link", link));
        return copy;
    }

    /** The data an anonymous caller receives, or empty when the type has no public view. */
    public static Optional<Map<String, Object>> forAnonymous(String type, Map<String, Object> data) {
        if (ALERT_TYPES.contains(type)) {
            return Optional.of(publicAlert(data));
        }
        if (!PUBLIC_KEYS.containsKey(type)) {
            return Optional.empty();
        }
        List<String> allowed = PUBLIC_KEYS.get(type);
        if (allowed == null) {
            return Optional.of(data);
        }
        return Optional.of(only(data, allowed));
    }

    /** DOC-32 E-20: no acknowledgement, and {@code body} only with the keys allowed for the alert's type. */
    private static Map<String, Object> publicAlert(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(forViewer("alert.created", data));
        copy.remove("acknowledgedBy");
        copy.remove("acknowledgedAt");
        Map<String, Object> body = body(data);
        List<String> allowed = alertType(data).map(Alert::publicBodyKeys).orElse(List.of());
        copy.put("body", only(body, allowed));
        return copy;
    }

    private static Optional<String> link(Map<String, Object> data) {
        return alertType(data)
                .map(type -> AlertLinks.of(type, text(data.get("routeId")), text(data.get("refId")), body(data)));
    }

    private static Optional<AlertType> alertType(Map<String, Object> data) {
        if (data.get("type") instanceof String name) {
            try {
                return Optional.of(AlertType.valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<String, Object> data) {
        return data.get("body") instanceof Map<?, ?> body ? (Map<String, Object>) body : Map.of();
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> only(Map<String, Object> data, List<String> keys) {
        Map<String, Object> kept = new LinkedHashMap<>();
        keys.stream().filter(data::containsKey).forEach(key -> kept.put(key, data.get(key)));
        return kept;
    }
}
