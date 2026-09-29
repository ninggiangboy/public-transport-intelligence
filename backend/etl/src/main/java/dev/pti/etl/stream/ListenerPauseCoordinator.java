package dev.pti.etl.stream;

import dev.pti.etl.flags.RuntimeFlagChanged;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Pauses and resumes listener containers for two independent reasons (DOC-20 §5.2, §7): a runtime flag, and the
 * {@code warehouse} circuit being open. A container resumes only when no reason is left. Back-off pauses belong to
 * the container error handler; {@link #reconcile()} re-applies the wanted state in case one of those resumes a
 * container that should stay paused.
 */
public class ListenerPauseCoordinator {

    /** Label {@code reason} of {@code pti_etl_listener_paused}. */
    public enum Reason {
        FLAG,
        CIRCUIT,
        BACKOFF;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ListenerPauseCoordinator.class);

    private final KafkaListenerEndpointRegistry registry;
    private final boolean baseline;
    private final Map<StreamListener, Set<Reason>> reasons = new EnumMap<>(StreamListener.class);

    public ListenerPauseCoordinator(KafkaListenerEndpointRegistry registry) {
        this(registry, false);
    }

    /** @param baseline true in the {@code experiment} profile, whose containers carry the baseline suffix */
    public ListenerPauseCoordinator(KafkaListenerEndpointRegistry registry, boolean baseline) {
        this.registry = registry;
        this.baseline = baseline;
        for (StreamListener l : StreamListener.values()) {
            reasons.put(l, EnumSet.noneOf(Reason.class));
        }
    }

    /** {@code pti_etl_listener_paused} and {@code pti_etl_listener_running} (DOC-20 §12). */
    public void bindTo(MeterRegistry meters, TopicNames topics) {
        for (StreamListener l : StreamListener.values()) {
            String topic = topics.of(l.source());
            for (Reason reason : Reason.values()) {
                Gauge.builder("pti.etl.listener.paused", () -> isPausedFor(l, reason) ? 1 : 0)
                        .tag("listener", l.id())
                        .tag("topic", topic)
                        .tag("reason", reason.tag())
                        .register(meters);
            }
            Gauge.builder("pti.etl.listener.running", () -> isRunning(l) ? 1 : 0)
                    .tag("listener", l.id())
                    .tag("topic", topic)
                    .register(meters);
        }
    }

    @EventListener
    public void onFlag(RuntimeFlagChanged event) {
        for (StreamListener l : StreamListener.pausedBy(event.key())) {
            set(l, Reason.FLAG, event.enabled());
        }
    }

    public void onCircuit(CircuitBreaker.StateTransition transition) {
        boolean open = transition.getToState() == CircuitBreaker.State.OPEN
                || transition.getToState() == CircuitBreaker.State.FORCED_OPEN;
        log.info("Warehouse circuit {}", transition);
        for (StreamListener l : StreamListener.values()) {
            set(l, Reason.CIRCUIT, open);
        }
    }

    public synchronized boolean isPausedFor(StreamListener listener, Reason reason) {
        if (reason == Reason.BACKOFF) {
            MessageListenerContainer c = container(listener);
            return c != null && c.isPauseRequested() && reasons.get(listener).isEmpty();
        }
        return reasons.get(listener).contains(reason);
    }

    public synchronized Set<Reason> reasons(StreamListener listener) {
        Set<Reason> all = EnumSet.noneOf(Reason.class);
        all.addAll(reasons.get(listener));
        if (isPausedFor(listener, Reason.BACKOFF)) {
            all.add(Reason.BACKOFF);
        }
        return all;
    }

    public boolean isRunning(StreamListener listener) {
        MessageListenerContainer c = container(listener);
        return c != null && c.isRunning();
    }

    /** Whether the container may run now; the lifecycle manager asks before starting one. */
    public synchronized boolean shouldBePaused(StreamListener listener) {
        return !reasons.get(listener).isEmpty();
    }

    public synchronized void reconcile() {
        for (StreamListener l : StreamListener.values()) {
            apply(l);
        }
    }

    private synchronized void set(StreamListener listener, Reason reason, boolean active) {
        boolean changed = active
                ? reasons.get(listener).add(reason)
                : reasons.get(listener).remove(reason);
        if (changed) {
            log.info("Listener {} {} ({})", listener.id(), active ? "pause requested" : "pause lifted", reason.tag());
            apply(listener);
        }
    }

    private void apply(StreamListener listener) {
        MessageListenerContainer c = container(listener);
        if (c == null || !c.isRunning()) {
            return;
        }
        boolean pause = !reasons.get(listener).isEmpty();
        if (pause && !c.isPauseRequested()) {
            c.pause();
        } else if (!pause && c.isPauseRequested()) {
            c.resume();
        }
    }

    private @Nullable MessageListenerContainer container(StreamListener listener) {
        return registry.getListenerContainer(listener.containerId(baseline));
    }
}
