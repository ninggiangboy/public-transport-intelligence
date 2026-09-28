package dev.pti.common.error;

import org.jspecify.annotations.Nullable;

/**
 * Root of the project's exceptions (DOC-30 §1). P2-02 adds {@code DataException}, {@code TransientInfraException}
 * and {@code ApiException} to the permitted subclasses.
 */
public abstract sealed class PtiException extends RuntimeException permits FatalException {

    private static final long serialVersionUID = 1L;

    protected PtiException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
