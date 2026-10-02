package dev.pti.api.stream.application.port;

import dev.pti.api.stream.domain.CloseReason;
import dev.pti.api.stream.domain.ReplayKind;
import dev.pti.common.events.UiChannel;
import java.time.Duration;

/** The SSE metrics of DOC-26 §12 and DOC-28 §3.5 that the hub records; gauges are read from the hub directly. */
public interface StreamMetrics {

    void opened(ReplayKind replay);

    void closed(CloseReason reason);

    void emitted(UiChannel channel, String type);

    /** {@code reason} is {@code slow_client} or {@code buffer_overflow}. */
    void dropped(String reason, int frames);

    /** Once per event and pod when it leaves the hub for the connections (DOC-28 §3.5). */
    void publishToEmit(UiChannel channel, Duration latency);

    /** As {@link #publishToEmit}, from the record time of the source data (DR-57, NFR-03). */
    void endToEnd(UiChannel channel, Duration latency);

    void invalidEvent();
}
