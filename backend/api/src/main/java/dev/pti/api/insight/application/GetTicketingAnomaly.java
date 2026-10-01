package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/** {@code GET /insights/ticketing-anomalies/{id}} (DOC-32 E-16): one anomaly with its window summary. */
public final class GetTicketingAnomaly {

    private final TicketingAnomalyReader anomalies;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public GetTicketingAnomaly(TicketingAnomalyReader anomalies, DataAsOfReader asOf, TransactionRunner tx) {
        this.anomalies = anomalies;
        this.asOf = asOf;
        this.tx = tx;
    }

    /** @throws NotFoundException when there is no such anomaly */
    public WithAsOf<TicketingAnomaly> execute(UUID id) {
        TicketingAnomaly anomaly = tx.inTransaction(() -> anomalies.find(id))
                .orElseThrow(() -> new NotFoundException("The ticketing anomaly does not exist."));
        return WithAsOf.of(anomaly, asOf.asOf(AsOfKind.TICKET_SALES));
    }
}
