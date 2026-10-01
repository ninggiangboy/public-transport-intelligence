package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.RuntimeFlag;
import java.util.Optional;

/** Changes {@code ops.runtime_flag} as {@code replay_operator} (DOC-32 E-57), in a transaction. */
public interface RuntimeFlagStore {

    /** The flag, read on the primary so that a write is seen at once. */
    Optional<RuntimeFlag> find(String key);

    /** Sets the value and who set it and when; empty when the flag does not exist. */
    Optional<RuntimeFlag> update(String key, Object value, String updatedBy);
}
