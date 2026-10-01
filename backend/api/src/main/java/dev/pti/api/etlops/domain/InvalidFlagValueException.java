package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The new value of a runtime flag has another JSON type than the current one (422 {@code invalid-flag-value}). */
public class InvalidFlagValueException extends ApiException {

    private static final long serialVersionUID = 1L;

    public InvalidFlagValueException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.INVALID_FLAG_VALUE;
    }
}
