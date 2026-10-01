package dev.pti.api.etlops.domain;

import java.util.List;

/** The {@code ops.dead_letter.status} values and their state machine (DOC-15 §4.3). */
public enum DeadLetterStatus {
    NEW,
    TRIAGING,
    TRIAGED,
    AUTO_REPLAY_SCHEDULED,
    PENDING_CONFIRM,
    MANUAL,
    REPLAY_REQUESTED,
    REPLAYED,
    DISCARDED,
    RESOLVED;

    /** The statuses that count as open, in the order of the summary (DOC-32 E-41). */
    public static final List<DeadLetterStatus> OPEN =
            List.of(NEW, TRIAGING, TRIAGED, AUTO_REPLAY_SCHEDULED, PENDING_CONFIRM, MANUAL, REPLAY_REQUESTED);

    /** Open means not {@code REPLAYED}, {@code DISCARDED} or {@code RESOLVED} (the gauge {@code pti_dlq_open_records}). */
    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
