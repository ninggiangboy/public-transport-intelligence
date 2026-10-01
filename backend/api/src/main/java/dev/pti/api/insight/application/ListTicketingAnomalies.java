package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /insights/ticketing-anomalies} (DOC-32 E-15): the anomalies detected in a range, newest first. */
public final class ListTicketingAnomalies {

    private final TicketingAnomalyReader anomalies;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public ListTicketingAnomalies(TicketingAnomalyReader anomalies, DataAsOfReader asOf, TransactionRunner tx) {
        this.anomalies = anomalies;
        this.asOf = asOf;
        this.tx = tx;
    }

    /** @return one page, as fresh as the last ticket sale */
    public WithAsOf<Page<TicketingAnomaly>> execute(TicketingQuery query, PageRequest request) {
        Page<TicketingAnomaly> page = tx.inTransaction(() -> anomalies.list(query, request));
        return WithAsOf.of(page, asOf.asOf(AsOfKind.TICKET_SALES));
    }
}
