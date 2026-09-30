package dev.pti.etl.analytics.application;

import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.analytics.event.domain.InsightEvent;
import java.util.ArrayList;
import java.util.List;

/** An {@link AnalyticsEventSink} that keeps what it is given, or fails on demand. */
public final class CollectingSink implements AnalyticsEventSink {

    private final List<InsightEvent> events = new ArrayList<>();
    private boolean failing;

    public CollectingSink failing() {
        failing = true;
        return this;
    }

    public List<InsightEvent> events() {
        return events;
    }

    @Override
    public void publish(List<InsightEvent> published) {
        events.addAll(published);
        if (failing) {
            throw new IllegalStateException("The sink broke its contract");
        }
    }
}
