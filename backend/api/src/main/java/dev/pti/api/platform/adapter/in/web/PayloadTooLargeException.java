package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The request body is larger than {@code pti.api.max-body-size} (413 {@code payload-too-large}). */
public class PayloadTooLargeException extends ApiException {

    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException(long maxBytes) {
        super("The request body is larger than " + maxBytes + " bytes.");
    }

    @Override
    public ProblemType type() {
        return ProblemType.PAYLOAD_TOO_LARGE;
    }
}
