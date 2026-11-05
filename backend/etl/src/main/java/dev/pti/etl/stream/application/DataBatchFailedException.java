package dev.pti.etl.stream.application;

import dev.pti.common.error.PtiException;

/**
 * Baseline {@code error-mode=fail-batch} (DR-27): one bad record fails the whole poll instead of being
 * dead-lettered. Only the {@code experiment} profile can turn this on.
 */
public final class DataBatchFailedException extends PtiException {

    private static final long serialVersionUID = 1L;

    public DataBatchFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
