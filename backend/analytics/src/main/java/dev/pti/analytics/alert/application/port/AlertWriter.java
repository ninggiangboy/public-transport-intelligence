package dev.pti.analytics.alert.application.port;

import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertRecord;
import java.util.Map;
import java.util.Optional;

/**
 * The life cycle of an alert in {@code ops.alert_event} (DOC-23 §10.2). The calls run in the transaction of the
 * episode or anomaly they belong to, so neither exists without the other. Each returns the row only when the
 * statement changed it; the caller emits a UI event only for a returned row, which is why running the same data
 * again emits nothing.
 */
public interface AlertWriter {

    /** Inserts the alert. Empty when an alert with the same dedup key exists already. */
    Optional<AlertRecord> open(AlertDraft draft);

    /**
     * Marks the alert resolved ({@code resolved_at = now()}) and merges {@code bodyPatch} into its body. Empty when
     * there is no open alert with this key.
     */
    Optional<AlertRecord> resolve(String dedupKey, Map<String, Object> bodyPatch);

    /**
     * Raises the severity of an open alert from 1 to 2 and merges {@code bodyPatch} into its body. Empty when the
     * alert is already at 2, resolved or missing.
     */
    Optional<AlertRecord> raiseSeverity(String dedupKey, Map<String, Object> bodyPatch);

    /**
     * Withdraws the alert of an episode that a recompute deleted (DOC-23 §11.2): {@code resolved_at} is set if it is
     * not yet and {@code {"withdrawn": true}} is merged into the body. A recompute emits no event for it, so no row
     * comes back.
     *
     * @return whether an alert with this key exists
     */
    boolean withdraw(String dedupKey);
}
