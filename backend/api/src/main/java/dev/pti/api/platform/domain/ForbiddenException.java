package dev.pti.api.platform.domain;

/**
 * The caller is known but may not do this, or may not see this (403 {@code forbidden}): a viewer asks for operator
 * data, an anonymous caller asks for another audience. A check the URL cannot express (DOC-27 §4); a rule that only
 * looks at the role is in {@code EndpointRules}.
 */
public class ForbiddenException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String detail) {
        super(detail);
    }

    @Override
    public ProblemType type() {
        return ProblemType.FORBIDDEN;
    }
}
