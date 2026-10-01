package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.BatchLineage;
import java.util.Optional;
import java.util.UUID;

/** Traces a {@code batch_id} to its micro-batch or step (DOC-32 E-37), as {@code api_reader}. */
public interface BatchLineageReader {

    /** The micro-batch with this id, else the step; empty when neither exists. */
    Optional<BatchLineage> find(UUID batchId);
}
