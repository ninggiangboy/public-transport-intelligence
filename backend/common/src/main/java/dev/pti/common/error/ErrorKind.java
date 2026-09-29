package dev.pti.common.error;

/** The three kinds of failure every worker distinguishes (ADR-0006, DOC-30 §2). */
public enum ErrorKind {
    /** One record is bad: skip it into the dead letter queue. */
    DATA,
    /** The infrastructure is unavailable for now: retry. */
    TRANSIENT_INFRA,
    /** A bug or a misconfiguration: stop. */
    FATAL
}
