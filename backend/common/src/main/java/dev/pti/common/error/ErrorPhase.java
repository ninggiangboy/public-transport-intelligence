package dev.pti.common.error;

/**
 * Where an exception was raised (DOC-30 §2.2). Parse errors are data errors only while reading input; the same
 * exception thrown while writing or configuring is a bug.
 */
public enum ErrorPhase {
    READ,
    PROCESS,
    WRITE,
    OTHER
}
