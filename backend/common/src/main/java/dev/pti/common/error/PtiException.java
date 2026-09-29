package dev.pti.common.error;

import org.jspecify.annotations.Nullable;

/**
 * Root of the project's exceptions (DOC-30 §1). {@code ApiException} joins the permitted subclasses with the API
 * in P4.
 */
public abstract sealed class PtiException extends RuntimeException
        permits DataException, TransientInfraException, FatalException {

    private static final long serialVersionUID = 1L;

    protected PtiException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /** For exceptions raised per record, where a stack trace costs CPU and tells nothing (DOC-30 §1). */
    protected PtiException(String message, @Nullable Throwable cause, boolean writableStackTrace) {
        super(message, cause, false, writableStackTrace);
    }
}
