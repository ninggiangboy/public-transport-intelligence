package dev.pti.etl.stream.adapter.in.event;

import dev.pti.etl.stream.application.MicroBatchCommitted;
import dev.pti.etl.stream.application.SourceActivity;
import org.springframework.context.event.EventListener;

/**
 * In-process listener that feeds every committed poll to {@link SourceActivity} (DOC-20 §6.1). The state itself stays
 * a plain application class, so the health indicators can read it without a Spring context.
 */
public class SourceActivityListener {

    private final SourceActivity activity;

    public SourceActivityListener(SourceActivity activity) {
        this.activity = activity;
    }

    @EventListener
    public void onCommitted(MicroBatchCommitted event) {
        activity.onCommitted(event);
    }
}
