package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The edit changes the entity type or business key of the record (422 {@code business-key-changed}, DOC-22 §2). */
public class BusinessKeyChangedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public BusinessKeyChangedException() {
        super("The edit changes the business key of the record. Discard it and let the source send a new one.");
    }

    @Override
    public ProblemType type() {
        return ProblemType.BUSINESS_KEY_CHANGED;
    }
}
