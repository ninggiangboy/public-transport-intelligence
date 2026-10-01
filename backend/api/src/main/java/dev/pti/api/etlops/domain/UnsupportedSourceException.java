package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The source has no raw zone to replay from (422 {@code unsupported-source}, DOC-22 §4.1). */
public class UnsupportedSourceException extends ApiException {

    private static final long serialVersionUID = 1L;

    public UnsupportedSourceException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.UNSUPPORTED_SOURCE;
    }
}
