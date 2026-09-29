package dev.pti.common.dq;

/** The step at which a record was rejected, {@code ops.dead_letter.stage} (DOC-16 §1, DR-69). */
public enum DlqStage {
    DESERIALIZE,
    SCHEMA,
    BUSINESS,
    DEDUP,
    LOAD,
    QUALITY
}
