package dev.pti.analytics.event.application.port;

import dev.pti.analytics.event.domain.InsightEvent;
import java.util.List;

/**
 * Where analytics sends its UI events after the transaction that produced them has committed (DOC-23 §3, ADR-0026).
 * The implementation in {@code etl} wraps each event in the DOC-09 §6 envelope and sends it to
 * {@code pti.events.ui}.
 */
public interface AnalyticsEventSink {

    /** Best-effort; must never throw. A failure is logged and counted, and the row in the database stays right. */
    void publish(List<InsightEvent> events);
}
