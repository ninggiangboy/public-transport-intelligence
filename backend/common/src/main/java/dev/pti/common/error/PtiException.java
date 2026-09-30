package dev.pti.common.error;

import org.jspecify.annotations.Nullable;

/**
 * Root of the project's exceptions (DOC-30 §1). Not sealed: {@code ApiException} lives in the {@code api} module, and
 * a sealed class would force it into this package, which the frozen architecture store of {@code common} does not
 * let grow (DOC-49 §9.2).
 */
public abstract class PtiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected PtiException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /** For exceptions raised per record, where a stack trace costs CPU and tells nothing (DOC-30 §1). */
    protected PtiException(String message, @Nullable Throwable cause, boolean writableStackTrace) {
        super(message, cause, false, writableStackTrace);
    }
}
