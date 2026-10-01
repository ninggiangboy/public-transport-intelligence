package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.domain.ReplayDetail;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/** {@code GET /etl/replays/{id}} (DOC-32 E-52): one replay, with the progress of its step while it runs. */
public final class GetReplay {

    private final ReplayReader replays;
    private final TransactionRunner tx;

    public GetReplay(ReplayReader replays, TransactionRunner tx) {
        this.replays = replays;
        this.tx = tx;
    }

    public ReplayDetail execute(UUID id) {
        return tx.inTransaction(() -> replays.find(id))
                .orElseThrow(() -> new NotFoundException("The replay does not exist."));
    }
}
