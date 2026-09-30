package dev.pti.analytics.retention.application.port;

/** {@code pti_retention_deleted_total{table}}: the rows a retention step deleted (DOC-18 §5). */
public interface RetentionMetrics {

    void deleted(String table, int count);
}
