package dev.pti.etl.stream.adapter.out.event;

import dev.pti.etl.stream.application.MicroBatchCommitted;
import dev.pti.etl.stream.application.port.BatchCommittedPublisher;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link BatchCommittedPublisher} on Spring's in-process event bus (DOC-19 §6.2): analytics and the UI feed listen
 * with {@code @EventListener}.
 */
public final class SpringBatchCommittedPublisher implements BatchCommittedPublisher {

    private final ApplicationEventPublisher events;

    public SpringBatchCommittedPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(MicroBatchCommitted event) {
        events.publishEvent(event);
    }
}
