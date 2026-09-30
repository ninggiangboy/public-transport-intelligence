package dev.pti.analytics.alert.domain;

/** Which rule of a ticketing anomaly fired (DOC-23 §9.3); it selects the alert title. */
public enum AnomalyTrigger {
    VOLUME,
    REFUND_RATIO,
    BOTH
}
