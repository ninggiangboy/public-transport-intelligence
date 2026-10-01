package dev.pti.apitest;

import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link UiEventPublisher} that keeps what it is given, so a test can see which events a write made. */
public final class RecordingUiEvents implements UiEventPublisher {

    private final List<UiEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(UiEvent event) {
        events.add(event);
    }

    @Override
    public void publishAll(List<UiEvent> batch) {
        batch.forEach(this::publish);
    }

    public List<UiEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
