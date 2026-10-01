package dev.pti.api.etlops.domain;

/** The {@code kind} of an {@code ops.job_request} (DOC-15 §3). */
public enum JobRequestKind {
    RUN,
    RESTART,
    STOP
}
