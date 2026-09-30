package dev.pti.api.system.application.port;

import dev.pti.api.system.domain.FreshnessSnapshot;
import java.util.Optional;

/** Where the result of the last probe is kept, in the memory of the pod (cache {@code freshness}, DOC-31 §10.3). */
public interface FreshnessSnapshots {

    Optional<FreshnessSnapshot> current();

    void store(FreshnessSnapshot snapshot);
}
