package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;

/**
 * {@code GET /insights/disruption} (DOC-32 E-12). An anonymous caller sees only the episodes whose alert is still
 * {@code PUBLIC} (FR-09.5 moves one to {@code ENGINEERING} to hide it from passengers); a viewer sees all of them.
 */
public final class ListDisruptionEpisodes {

    private final DisruptionReader episodes;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public ListDisruptionEpisodes(DisruptionReader episodes, DataAsOfReader asOf, TransactionRunner tx) {
        this.episodes = episodes;
        this.asOf = asOf;
        this.tx = tx;
    }

    /**
     * @param filter the range, routes and status; the audience is decided here, from the caller
     * @return one page, as fresh as the last trip update
     */
    public WithAsOf<Page<DisruptionEpisode>> execute(Caller caller, DisruptionQuery filter, PageRequest request) {
        DisruptionQuery query =
                new DisruptionQuery(filter.from(), filter.to(), filter.routeIds(), filter.status(), !caller.isViewer());
        Page<DisruptionEpisode> page = tx.inTransaction(() -> episodes.list(query, request));
        return WithAsOf.of(page, asOf.asOf(AsOfKind.TRIP_UPDATE));
    }
}
