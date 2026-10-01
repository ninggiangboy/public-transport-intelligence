package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.common.tx.TransactionRunner;
import java.util.List;

/** {@code GET /etl/flags} (DOC-32 E-55): every runtime flag, ordered by key. */
public final class ListRuntimeFlags {

    private final RuntimeFlagReader flags;
    private final TransactionRunner tx;

    public ListRuntimeFlags(RuntimeFlagReader flags, TransactionRunner tx) {
        this.flags = flags;
        this.tx = tx;
    }

    public List<RuntimeFlag> execute() {
        return tx.inTransaction(flags::list);
    }
}
