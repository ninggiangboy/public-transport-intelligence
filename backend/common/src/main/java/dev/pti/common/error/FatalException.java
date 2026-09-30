package dev.pti.common.error;

import org.jspecify.annotations.Nullable;

/** A bug or a misconfiguration (DOC-30 §1): stops the step, the listener container or the application start. */
public class FatalException extends PtiException {

    private static final long serialVersionUID = 1L;

    public FatalException(String message) {
        super(message, null);
    }

    public FatalException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
