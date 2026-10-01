package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Changes dead letters as {@code replay_operator} (DOC-32 §7), always in a transaction. Every change is a conditional
 * {@code UPDATE} that names the statuses it may start from (DOC-15 §4.3), so that nothing a triage worker or the ETL
 * moved in the meantime is overwritten.
 */
public interface DeadLetterStore {

    /** What a successful change did: the status before, and the columns the callers log or publish. */
    record Changed(
            DeadLetterStatus previous,
            String source,
            @Nullable BigDecimal categoryConfidence) {}

    /** The dead letter with its actions and replays, read on the primary so that a write is seen at once. */
    Optional<DeadLetterDetail> find(UUID id);

    /**
     * Moves the dead letter to {@code to} when it is in one of {@code from}.
     *
     * @param closedBy for {@code DISCARDED} and {@code RESOLVED}: who closed it ({@code resolved_by}, {@code
     *     resolved_at}); {@code null} otherwise
     * @return empty when the dead letter is missing or not in one of the statuses
     */
    Optional<Changed> transition(UUID id, Set<DeadLetterStatus> from, DeadLetterStatus to, @Nullable String closedBy);

    /** Stores the edited payload ({@code edited_payload}, {@code updated_at}); the status stays. */
    Optional<Changed> saveEditedPayload(UUID id, Set<DeadLetterStatus> from, String payloadJson);

    /** Appends a line to {@code dlq_action_log}. */
    void log(UUID id, String action, String actor, @Nullable BigDecimal confidence, Map<String, Object> details);
}
