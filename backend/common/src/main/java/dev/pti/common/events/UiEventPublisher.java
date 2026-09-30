package dev.pti.common.events;

import java.util.List;

/**
 * Port for publishing UI events to {@code pti.events.ui} (DOC-33 §2.1, ADR-0026). The port is shared so that every
 * producer speaks the same type; each app implements it in its own {@code adapter.out.kafka}, because {@code common}
 * does not depend on Spring Kafka (A-01).
 */
public interface UiEventPublisher {

    /** Best-effort, after commit. Never throws; failures are logged and counted. */
    void publish(UiEvent event);

    /** {@link #publish} for each event, in order. */
    void publishAll(List<UiEvent> events);
}
