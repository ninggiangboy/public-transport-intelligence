package dev.pti.etl.write;

/** How a chunk reached the warehouse: one batch write, or item by item after a data error (DOC-19 §6.2). */
public enum WriteMode {
    BATCH,
    SCAN
}
