package dev.pti.api.alert.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.pti.api.alert.domain.AlertmanagerAlert;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ValidationException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The webhook payload of Alertmanager, version 4 (DOC-32 E-80). Only what the API uses is read: Alertmanager sends more
 * ({@code receiver}, {@code groupLabels}, {@code externalURL}, …), and more may come, so unknown members are ignored
 * here, unlike in a request body of the public API. The {@code alerts} are checked one by one, and one that is not
 * well formed fails the whole request with a 400 so that Alertmanager sends it again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(@Nullable List<Notice> alerts) {

    /** One element of {@code alerts[]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notice(
            @Nullable String status,
            @Nullable Map<String, String> labels,
            @Nullable Map<String, String> annotations,
            @Nullable String startsAt,
            @Nullable String generatorURL,
            @Nullable String fingerprint) {}

    /**
     * @throws ValidationException naming the member of the first notice that is wrong
     */
    List<AlertmanagerAlert> toAlerts() {
        if (alerts == null) {
            throw ValidationException.of("alerts", "is required");
        }
        List<AlertmanagerAlert> result = new ArrayList<>();
        for (int i = 0; i < alerts.size(); i++) {
            result.add(toAlert(alerts.get(i), "alerts[" + i + "]"));
        }
        return result;
    }

    private static AlertmanagerAlert toAlert(@Nullable Notice notice, String at) {
        List<FieldError> errors = new ArrayList<>();
        if (notice == null) {
            throw ValidationException.of(at, "must be an object");
        }
        AlertmanagerAlert.Status status = status(notice.status());
        String fingerprint = notice.fingerprint();
        String startsAt = notice.startsAt();
        Map<String, String> labels = notice.labels();
        Map<String, String> annotations = notice.annotations();
        if (status == null) {
            errors.add(new FieldError(at + ".status", "must be firing or resolved"));
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            errors.add(new FieldError(at + ".fingerprint", "is required"));
        }
        if (!isTimestamp(startsAt)) {
            errors.add(new FieldError(at + ".startsAt", "must be an RFC 3339 time"));
        }
        if (labels == null || !labels.containsKey("alertname")) {
            errors.add(new FieldError(at + ".labels.alertname", "is required"));
        }
        if (!errors.isEmpty() || status == null || fingerprint == null || startsAt == null || labels == null) {
            throw new ValidationException("The notice is not valid.", errors);
        }
        return new AlertmanagerAlert(
                status,
                fingerprint,
                startsAt,
                labels,
                annotations == null ? Map.of() : annotations,
                notice.generatorURL());
    }

    private static AlertmanagerAlert.@Nullable Status status(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "firing" -> AlertmanagerAlert.Status.FIRING;
            case "resolved" -> AlertmanagerAlert.Status.RESOLVED;
            default -> null;
        };
    }

    private static boolean isTimestamp(@Nullable String text) {
        if (text == null) {
            return false;
        }
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
