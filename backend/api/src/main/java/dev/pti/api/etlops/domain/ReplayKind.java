package dev.pti.api.etlops.domain;

/** The {@code kind} of an {@code ops.replay_request} (DOC-15 §3). */
public enum ReplayKind {
    RAW_RANGE,
    DLQ_RECORD
}
