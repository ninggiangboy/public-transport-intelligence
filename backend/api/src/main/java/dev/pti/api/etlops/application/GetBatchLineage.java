package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.BatchLineageReader;
import dev.pti.api.etlops.domain.BatchLineage;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;

/** {@code GET /etl/batches/{batchId}} (DOC-32 E-37, FR-12.5): which micro-batch or step a {@code batch_id} is. */
public final class GetBatchLineage {

    private final BatchLineageReader lineage;
    private final TransactionRunner tx;

    public GetBatchLineage(BatchLineageReader lineage, TransactionRunner tx) {
        this.lineage = lineage;
        this.tx = tx;
    }

    public BatchLineage execute(UUID batchId) {
        return tx.inTransaction(() -> lineage.find(batchId))
                .orElseThrow(() -> new NotFoundException("The batch does not exist."));
    }
}
