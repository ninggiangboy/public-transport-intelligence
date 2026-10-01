package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/**
 * {@code GET /insights/disruption/{id}} (DOC-32 E-13). An episode that is not public does not exist for an anonymous
 * caller: the answer is the same 404 as for an unknown id, so its existence is not revealed.
 */
public final class GetDisruptionEpisode {

    private final DisruptionReader episodes;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public GetDisruptionEpisode(DisruptionReader episodes, DataAsOfReader asOf, TransactionRunner tx) {
        this.episodes = episodes;
        this.asOf = asOf;
        this.tx = tx;
    }

    /** @throws NotFoundException when there is no such episode, or the caller may not see it */
    public WithAsOf<DisruptionEpisode> execute(Caller caller, UUID id) {
        DisruptionEpisode episode = tx.inTransaction(() -> episodes.find(id))
                .filter(found -> caller.isViewer() || found.isPublic())
                .orElseThrow(() -> new NotFoundException("The disruption episode does not exist."));
        return WithAsOf.of(episode, asOf.asOf(AsOfKind.TRIP_UPDATE));
    }
}
