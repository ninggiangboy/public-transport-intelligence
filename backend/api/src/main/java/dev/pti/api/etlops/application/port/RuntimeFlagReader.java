package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.RuntimeFlag;
import java.util.List;
import java.util.Optional;

/** Reads {@code ops.runtime_flag} (DOC-32 E-55, E-56), as {@code api_reader}. */
public interface RuntimeFlagReader {

    /** Every flag, ordered by key. */
    List<RuntimeFlag> list();

    Optional<RuntimeFlag> find(String key);
}
