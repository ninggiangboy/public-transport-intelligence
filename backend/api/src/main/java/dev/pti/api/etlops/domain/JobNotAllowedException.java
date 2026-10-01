package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The job is not one that may be started by hand (422 {@code job-not-allowed}, DOC-32 E-33). */
public class JobNotAllowedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public JobNotAllowedException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.JOB_NOT_ALLOWED;
    }
}
