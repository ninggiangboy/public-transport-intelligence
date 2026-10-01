package dev.pti.api.alert.application.port;

/**
 * Counts what happens to the notices of the Alertmanager webhook into {@code pti_alert_webhook_total{outcome}} (DOC-32
 * E-80): {@code created}, {@code duplicate}, {@code resolved}, {@code unknown_resolved} or {@code rejected}.
 */
public interface WebhookMetrics {

    String CREATED = "created";
    String DUPLICATE = "duplicate";
    String RESOLVED = "resolved";
    String UNKNOWN_RESOLVED = "unknown_resolved";
    String REJECTED = "rejected";

    void outcome(String outcome);
}
