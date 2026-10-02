package dev.pti.api.alert.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public projection of an alert (DOC-32 E-20, DOC-33 §4): what an anonymous caller may see of one that is {@code
 * PUBLIC}. The acknowledgement is dropped, and {@code body} keeps only the keys that are allowed for the type, so what
 * the triage worker adds later (a cause, a model version) never reaches a passenger. A type without a list keeps an
 * empty body: the safe default.
 */
final class AlertProjection {

    private static final Map<AlertType, List<String>> PUBLIC_BODY_KEYS = Map.of(
            AlertType.DISRUPTION,
            List.of(
                    "disruptionId",
                    "directionId",
                    "episodeStart",
                    "episodeEnd",
                    "currentAvgDelaySeconds",
                    "affectedStopIds"));

    private AlertProjection() {}

    static List<String> publicBodyKeys(AlertType type) {
        return PUBLIC_BODY_KEYS.getOrDefault(type, List.of());
    }

    static Alert forAnonymous(Alert alert) {
        List<String> allowed = publicBodyKeys(alert.type());
        Map<String, Object> body = new LinkedHashMap<>();
        allowed.stream()
                .filter(alert.body()::containsKey)
                .forEach(key -> body.put(key, alert.body().get(key)));
        return new Alert(
                alert.id(),
                alert.type(),
                alert.severity(),
                alert.audience(),
                alert.routeId(),
                alert.refTable(),
                alert.refId(),
                alert.title(),
                body,
                alert.createdAt(),
                null,
                null,
                alert.resolvedAt());
    }
}
