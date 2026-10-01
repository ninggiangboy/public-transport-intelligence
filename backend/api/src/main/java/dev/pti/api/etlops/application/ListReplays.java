package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.domain.ReplayFilter;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/replays} (DOC-32 E-51): the replays of a window, newest first. */
public final class ListReplays {

    private final ReplayReader replays;
    private final TransactionRunner tx;

    public ListReplays(ReplayReader replays, TransactionRunner tx) {
        this.replays = replays;
        this.tx = tx;
    }

    public Page<ReplayRequest> execute(ReplayFilter filter, PageRequest page) {
        return tx.inTransaction(() -> replays.list(filter, page));
    }
}
