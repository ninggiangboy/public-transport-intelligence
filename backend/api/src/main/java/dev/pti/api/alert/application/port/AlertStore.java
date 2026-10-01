package dev.pti.api.alert.application.port;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.NewAlert;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the API writes alerts and reads back what it wrote (DOC-32 E-21, E-80), through the primary. The API changes
 * only the acknowledgement and {@code resolved_at}, and inserts only what Alertmanager sends (DOC-17); every method
 * answers with the row as the statement left it, which is what the UI event carries.
 */
public interface AlertStore {

    Optional<Alert> find(UUID id);

    /**
     * Sets the acknowledgement if the alert has none.
     *
     * @return the updated row; empty when the alert is unknown or was already acknowledged
     */
    Optional<Alert> acknowledge(UUID id, String actor);

    /** @return the new row; empty when an alert with the same dedup key exists */
    Optional<Alert> insertIfAbsent(NewAlert alert);

    /**
     * Marks the alert with this dedup key resolved, now.
     *
     * @return the updated row; empty when there is none, or it was resolved already
     */
    Optional<Alert> resolve(String dedupKey);
}
