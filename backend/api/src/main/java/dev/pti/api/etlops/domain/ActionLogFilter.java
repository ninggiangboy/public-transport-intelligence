package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The filters of {@code GET /etl/dlq/actions} (DOC-32 E-48). */
public record ActionLogFilter(
        Instant from,
        Instant to,
        List<String> actions,
        @Nullable ActorType actorType,
        @Nullable UUID deadLetterId) {

    /** Who did it: {@code auto}, a {@code system:<service>} or a {@code user:<name>}. */
    public enum ActorType {
        AUTO,
        SYSTEM,
        USER
    }

    public ActionLogFilter {
        actions = List.copyOf(actions);
    }
}
