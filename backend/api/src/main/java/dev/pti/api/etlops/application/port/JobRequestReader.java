package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.JobRequest;
import java.util.Optional;
import java.util.UUID;

/** Reads {@code ops.job_request} (DOC-32 E-34), as {@code api_reader}. */
public interface JobRequestReader {

    Optional<JobRequest> find(UUID id);
}
