package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;
import java.util.List;

/** The edited payload is not JSON or does not pass the schema (422 {@code invalid-payload}, DOC-22 §2). */
public class InvalidPayloadException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public InvalidPayloadException(String detail, List<FieldError> errors) {
        super(detail);
        this.errors = List.copyOf(errors);
    }

    @Override
    public ProblemType type() {
        return ProblemType.INVALID_PAYLOAD;
    }

    @Override
    public List<FieldError> errors() {
        return errors;
    }
}
