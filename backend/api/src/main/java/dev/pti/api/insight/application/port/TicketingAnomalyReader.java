package dev.pti.api.insight.application.port;

import dev.pti.api.insight.application.TicketingQuery;
import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.util.Optional;
import java.util.UUID;

/** Reads ticketing anomalies with the name and route of their sale point (DOC-32 E-15, E-16). */
public interface TicketingAnomalyReader {

    /** Newest {@code detected_at} first, then {@code id} descending. */
    Page<TicketingAnomaly> list(TicketingQuery query, PageRequest request);

    Optional<TicketingAnomaly> find(UUID id);
}
