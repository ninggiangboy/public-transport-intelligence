package dev.pti.api.platform.domain;

/**
 * The id is unknown, or malformed: for the caller they are the same (DOC-31 §9). The detail names the kind of resource
 * and never echoes the value back.
 */
public class NotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.NOT_FOUND;
    }
}
