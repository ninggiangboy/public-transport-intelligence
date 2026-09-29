package dev.pti.etl.stream;

import dev.pti.etl.reference.ReferenceDataChanged;
import dev.pti.etl.reference.ReferenceDataHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Starts the GTFS-realtime listeners once an ACTIVE feed is known (DOC-20 §6): without it every record would fail
 * DQ-03 to DQ-06. A listener whose pause flag is on is started and paused at once.
 */
public class ListenerLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(ListenerLifecycleManager.class);

    private final KafkaListenerEndpointRegistry registry;
    private final ReferenceDataHolder reference;
    private final ListenerPauseCoordinator pauses;
    private final boolean baseline;

    public ListenerLifecycleManager(
            KafkaListenerEndpointRegistry registry, ReferenceDataHolder reference, ListenerPauseCoordinator pauses) {
        this(registry, reference, pauses, false);
    }

    public ListenerLifecycleManager(
            KafkaListenerEndpointRegistry registry,
            ReferenceDataHolder reference,
            ListenerPauseCoordinator pauses,
            boolean baseline) {
        this.baseline = baseline;
        this.registry = registry;
        this.reference = reference;
        this.pauses = pauses;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        startIfReady();
    }

    @EventListener
    public void onReferenceData(ReferenceDataChanged event) {
        startIfReady();
    }

    synchronized void startIfReady() {
        if (!reference.isLoaded()) {
            log.info("No ACTIVE GTFS feed yet: GTFS-realtime listeners stay stopped");
            return;
        }
        for (StreamListener l : StreamListener.values()) {
            MessageListenerContainer c = registry.getListenerContainer(l.containerId(baseline));
            if (l.needsReferenceData() && c != null && !c.isRunning()) {
                if (pauses.shouldBePaused(l)) {
                    c.pause();
                }
                c.start();
                log.info("Listener {} started", l.containerId(baseline));
            }
        }
    }
}
