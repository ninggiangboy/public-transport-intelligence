package dev.pti.etl.maintenance.application.port;

/** {@code pti_retention_deleted_total{table}}: rows, or partitions for the fact tables (DOC-18 §5). */
public interface RetentionMetrics {

    void deleted(String table, long count);
}
