package dev.pti.api.platform.domain;

import java.util.List;

/** A parameter or body the API cannot accept (400 {@code validation-error}), with the offending fields. */
public class ValidationException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public ValidationException(String detail, List<FieldError> errors) {
        super(detail);
        this.errors = List.copyOf(errors);
    }

    /** One field, the common case. */
    public static ValidationException of(String field, String message) {
        return new ValidationException("The request is not valid.", List.of(new FieldError(field, message)));
    }

    @Override
    public ProblemType type() {
        return ProblemType.VALIDATION_ERROR;
    }

    @Override
    public List<FieldError> errors() {
        return errors;
    }
}
