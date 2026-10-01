package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;
import java.util.List;

/** The window of a raw zone replay breaks the rules of DOC-22 §4.1 (422 {@code replay-window-invalid}). */
public class ReplayWindowInvalidException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public ReplayWindowInvalidException(List<FieldError> errors) {
        super("The replay window is not valid.");
        this.errors = List.copyOf(errors);
    }

    @Override
    public ProblemType type() {
        return ProblemType.REPLAY_WINDOW_INVALID;
    }

    @Override
    public List<FieldError> errors() {
        return errors;
    }
}
