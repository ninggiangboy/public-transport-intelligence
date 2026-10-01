package dev.pti.api.alert.domain;

import dev.pti.common.events.Audience;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An alert to insert: the columns the API writes, which is only for Alertmanager (DOC-32 E-80). */
public record NewAlert(
        UUID id,
        AlertType type,
        int severity,
        Audience audience,
        @Nullable String routeId,
        String title,
        Map<String, Object> body,
        String dedupKey) {}
