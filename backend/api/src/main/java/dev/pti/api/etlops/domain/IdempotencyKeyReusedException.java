package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The {@code Idempotency-Key} was used for a different request (422 {@code idempotency-key-reused}, DOC-31 §8). */
public class IdempotencyKeyReusedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException() {
        super("The Idempotency-Key was already used for a different request.");
    }

    @Override
    public ProblemType type() {
        return ProblemType.IDEMPOTENCY_KEY_REUSED;
    }
}
