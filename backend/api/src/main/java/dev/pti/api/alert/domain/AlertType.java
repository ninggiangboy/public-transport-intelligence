package dev.pti.api.alert.domain;

import java.util.Arrays;
import java.util.List;

/** The kinds of alert of {@code ops.alert_event} (DOC-15 §3, ADR-0023); the constant name is the value in the column. */
public enum AlertType {
    DISRUPTION,
    BUNCHING,
    DLQ_SEVERE,
    FEED_STALE,
    TICKETING_ANOMALY,
    INFRA;

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
