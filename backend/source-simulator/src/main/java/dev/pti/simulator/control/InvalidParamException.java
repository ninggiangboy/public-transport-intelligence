package dev.pti.simulator.control;

import java.util.List;

/** Answered with 400 {@code urn:pti:problem:invalid-param}. */
public class InvalidParamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public InvalidParamException(String detail, List<FieldError> errors) {
        super(detail);
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    /** @param field the camelCase name in the request */
    public record FieldError(String field, String message) {}
}
