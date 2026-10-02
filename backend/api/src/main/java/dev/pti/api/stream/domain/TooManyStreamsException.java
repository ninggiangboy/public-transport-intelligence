package dev.pti.api.stream.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The caller already holds as many {@code /stream} connections as DOC-31 §11 allows (429 {@code rate-limited}). */
public class TooManyStreamsException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** The client retries after its usual backoff anyway; this is the hint for a well-behaved one. */
    static final int RETRY_AFTER_SECONDS = 5;

    public TooManyStreamsException(int limit) {
        super("At most " + limit + " event streams are allowed at once for this caller.");
    }

    @Override
    public ProblemType type() {
        return ProblemType.RATE_LIMITED;
    }

    @Override
    public Integer retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }
}
