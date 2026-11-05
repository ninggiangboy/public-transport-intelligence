package dev.pti.etl.reference.application.port;

import dev.pti.etl.reference.domain.ReferenceData;
import java.util.Optional;

/** Reads feed versions from the warehouse for the reference data of this JVM (DOC-21 §6.1). */
public interface ReferenceDataSource {

    /** The id of the ACTIVE feed version, if any. */
    Optional<Long> activeFeedVersionId();

    ReferenceData load(long feedVersionId);
}
