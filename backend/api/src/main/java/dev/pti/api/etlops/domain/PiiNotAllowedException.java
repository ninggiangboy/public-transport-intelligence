package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The edited payload holds a key of the PII blocklist (422 {@code pii-not-allowed}, DOC-22 §2). */
public class PiiNotAllowedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public PiiNotAllowedException() {
        super("The payload contains a field that is personal data and cannot be stored.");
    }

    @Override
    public ProblemType type() {
        return ProblemType.PII_NOT_ALLOWED;
    }
}
