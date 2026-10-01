package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/flags/{key}} (DOC-32 E-56): one runtime flag. */
public final class GetRuntimeFlag {

    private final RuntimeFlagReader flags;
    private final TransactionRunner tx;

    public GetRuntimeFlag(RuntimeFlagReader flags, TransactionRunner tx) {
        this.flags = flags;
        this.tx = tx;
    }

    public RuntimeFlag execute(String key) {
        return tx.inTransaction(() -> flags.find(key))
                .orElseThrow(() -> new NotFoundException("The runtime flag does not exist."));
    }
}
