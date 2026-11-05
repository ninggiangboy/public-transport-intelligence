package dev.pti.common.error;

/**
 * Sorts any exception into {@link ErrorKind} (ADR-0006, DOC-30 §2). The use cases depend on this port; the rules
 * live in {@code dev.pti.common.classifier.PlatformErrorClassifier}, which knows {@code java.sql} and the library
 * exceptions by name (DR-115).
 */
public interface ErrorClassifier {

    /** Classifies an exception raised outside of reading or processing input. */
    default ErrorKind classify(Throwable throwable) {
        return classify(throwable, ErrorPhase.OTHER);
    }

    ErrorKind classify(Throwable throwable, ErrorPhase phase);
}
