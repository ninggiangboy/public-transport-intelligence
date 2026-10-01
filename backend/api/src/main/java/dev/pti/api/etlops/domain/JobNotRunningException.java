package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The run is not running, so it cannot be stopped (409 {@code job-not-running}, DOC-32 E-36). */
public class JobNotRunningException extends ApiException {

    private static final long serialVersionUID = 1L;

    public JobNotRunningException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.JOB_NOT_RUNNING;
    }
}
