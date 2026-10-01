package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The run cannot be restarted (409 {@code job-not-restartable}, DOC-32 E-35); the detail says why. */
public class JobNotRestartableException extends ApiException {

    private static final long serialVersionUID = 1L;

    public JobNotRestartableException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.JOB_NOT_RESTARTABLE;
    }
}
