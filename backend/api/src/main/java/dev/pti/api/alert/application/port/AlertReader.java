package dev.pti.api.alert.application.port;

import dev.pti.api.alert.application.AlertQuery;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;

/** Reads the alert feed (DOC-32 E-20). */
public interface AlertReader {

    /**
     * Newest {@code created_at} first, then {@code id} descending.
     *
     * @param query {@code audiences} is the set the caller may see and is never empty here
     */
    Page<Alert> list(AlertQuery query, PageRequest request);
}
