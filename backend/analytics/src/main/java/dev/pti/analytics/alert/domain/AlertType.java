package dev.pti.analytics.alert.domain;

import dev.pti.common.events.Audience;
import java.util.UUID;

/**
 * The alert types that analytics writes, with what DOC-23 §10.1 fixes per type: the row the alert points at, the
 * prefix of its dedup key and the audience it starts with. {@code audience} is the minimum visibility (ADR-0023);
 * analytics never changes it after the insert.
 */
public enum AlertType {
    BUNCHING("insight.insight_bus_bunching", "bunching", Audience.OPERATIONS),
    DISRUPTION("insight.insight_service_disruption", "disruption", Audience.PUBLIC),
    TICKETING_ANOMALY("insight.insight_ticketing_anomaly", "ticketing", Audience.OPERATIONS);

    private final String refTable;
    private final String dedupPrefix;
    private final Audience audience;

    AlertType(String refTable, String dedupPrefix, Audience audience) {
        this.refTable = refTable;
        this.dedupPrefix = dedupPrefix;
        this.audience = audience;
    }

    /** {@code alert_event.ref_table}: the table of the episode or anomaly the alert is about. */
    public String refTable() {
        return refTable;
    }

    /** The audience a new alert of this type gets. */
    public Audience audience() {
        return audience;
    }

    /** {@code alert_event.dedup_key}: {@code bunching:<id>}, {@code disruption:<id>} or {@code ticketing:<id>}. */
    public String dedupKey(UUID refId) {
        return dedupPrefix + ":" + refId;
    }
}
