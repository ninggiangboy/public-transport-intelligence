package dev.pti.etl.realtime.application.port;

import dev.pti.common.events.UiEvent;
import java.time.Instant;

/** Sends a UI event to {@code pti.events.ui}, best effort (ADR-0026); never throws. */
public interface RealtimeEventSink {

    /** @param committedAt when the oldest data in the event was committed, for {@code pti_ui_commit_to_publish} */
    void publish(UiEvent event, Instant committedAt);
}
