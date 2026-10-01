package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/** {@code GET /insights/bunching/{id}} (DOC-32 E-11): one episode with its whole dispatch suggestion. */
public final class GetBunchingEpisode {

    private final BunchingReader episodes;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public GetBunchingEpisode(BunchingReader episodes, DataAsOfReader asOf, TransactionRunner tx) {
        this.episodes = episodes;
        this.asOf = asOf;
        this.tx = tx;
    }

    /** @throws NotFoundException when there is no such episode */
    public WithAsOf<BunchingDetail> execute(UUID id) {
        BunchingDetail detail = tx.inTransaction(() -> episodes.find(id))
                .orElseThrow(() -> new NotFoundException("The bunching episode does not exist."));
        return WithAsOf.of(detail, asOf.asOf(AsOfKind.VEHICLE_POSITION));
    }
}
