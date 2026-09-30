package dev.pti.common.error;

import org.jspecify.annotations.Nullable;

/** The record is fine, the infrastructure is not (DOC-30 §1): retried, never dead-lettered. */
public class TransientInfraException extends PtiException {

    private static final long serialVersionUID = 1L;

    public TransientInfraException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
