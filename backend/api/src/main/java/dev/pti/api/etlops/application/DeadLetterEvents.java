package dev.pti.api.etlops.application;

import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The {@code dlq.changed} events the API publishes after it changed a dead letter (DOC-33 §5.8, kind {@code UPDATED}). */
final class DeadLetterEvents {

    private DeadLetterEvents() {}

    /**
     * @param now the real time, as {@code occurredAt}
     * @param previous the status before the change
     * @param action the {@code dlq_action_log.action} of the change
     */
    static UiEvent updated(
            Instant now,
            UUID id,
            String source,
            DeadLetterStatus status,
            DeadLetterStatus previous,
            String action,
            String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "UPDATED");
        data.put("id", id.toString());
        data.put("source", source);
        data.put("status", status.name());
        data.put("previousStatus", previous.name());
        data.put("action", action);
        data.put("actor", actor);
        return UiEvent.of(now, "dlq.changed", UiChannel.DLQ, Audience.ENGINEERING, id.toString(), null, null, data);
    }
}
