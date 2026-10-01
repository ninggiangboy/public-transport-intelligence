package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /insights/bunching} (DOC-32 E-10): the bunching episodes of a range, newest first. */
public final class ListBunchingEpisodes {

    private final BunchingReader episodes;
    private final DataAsOfReader asOf;
    private final TransactionRunner tx;

    public ListBunchingEpisodes(BunchingReader episodes, DataAsOfReader asOf, TransactionRunner tx) {
        this.episodes = episodes;
        this.asOf = asOf;
        this.tx = tx;
    }

    /** @return one page, as fresh as the last vehicle position */
    public WithAsOf<Page<BunchingEpisode>> execute(BunchingQuery query, PageRequest request) {
        Page<BunchingEpisode> page = tx.inTransaction(() -> episodes.list(query, request));
        return WithAsOf.of(page, asOf.asOf(AsOfKind.VEHICLE_POSITION));
    }
}
