package dev.pti.api.etlops.domain;

import static dev.pti.api.etlops.domain.DeadLetterStatus.MANUAL;
import static dev.pti.api.etlops.domain.DeadLetterStatus.NEW;
import static dev.pti.api.etlops.domain.DeadLetterStatus.PENDING_CONFIRM;
import static dev.pti.api.etlops.domain.DeadLetterStatus.TRIAGED;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * What an operator may do with a dead letter and from which status (DOC-32 §7, DOC-15 §4.3). The table is the single
 * place that says it: the use cases check against it, and {@code allowedActions} of E-42 is derived from it.
 */
public enum DeadLetterAction {
    EDIT(EnumSet.of(NEW, TRIAGED, PENDING_CONFIRM, MANUAL), null, "EDITED"),
    REPLAY(EnumSet.of(NEW, MANUAL), DeadLetterStatus.REPLAY_REQUESTED, "REPLAY_REQUESTED"),
    CONFIRM(EnumSet.of(PENDING_CONFIRM), DeadLetterStatus.REPLAY_REQUESTED, "CONFIRMED"),
    DISCARD(EnumSet.of(NEW, MANUAL, PENDING_CONFIRM), DeadLetterStatus.DISCARDED, "DISCARDED"),
    RESOLVE(EnumSet.of(MANUAL), DeadLetterStatus.RESOLVED, "RESOLVED");

    private final Set<DeadLetterStatus> from;
    private final @Nullable DeadLetterStatus to;
    private final String logAction;

    DeadLetterAction(Set<DeadLetterStatus> from, @Nullable DeadLetterStatus to, String logAction) {
        this.from = Set.copyOf(from);
        this.to = to;
        this.logAction = logAction;
    }

    /** The statuses the action may start from. */
    public Set<DeadLetterStatus> from() {
        return from;
    }

    /** The status after the action; {@code null} for an edit, which keeps it. */
    public @Nullable DeadLetterStatus to() {
        return to;
    }

    /** The first {@code dlq_action_log.action} the action writes. */
    public String logAction() {
        return logAction;
    }

    public boolean allows(DeadLetterStatus status) {
        return from.contains(status);
    }

    /** The name in {@code allowedActions} and in messages: {@code edit}, {@code replay}, … */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The actions a caller may take now: none for a viewer (DOC-32 E-42). */
    public static List<String> allowedFor(DeadLetterStatus status, boolean operator) {
        if (!operator) {
            return List.of();
        }
        return Stream.of(values())
                .filter(action -> action.allows(status))
                .map(DeadLetterAction::wire)
                .toList();
    }
}
