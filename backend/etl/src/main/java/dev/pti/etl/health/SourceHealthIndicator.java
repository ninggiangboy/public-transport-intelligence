package dev.pti.etl.health;

import dev.pti.etl.stream.ListenerPauseCoordinator;
import dev.pti.etl.stream.ListenerPauseCoordinator.Reason;
import dev.pti.etl.stream.SourceActivity;
import dev.pti.etl.stream.StreamListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * {@code source-gtfs-rt} and {@code source-ticketing} (DR-38, DOC-20 §6.1): the warehouse is up, the listeners of
 * the source run without back-off or circuit pauses, and data arrived recently. A flag pause is
 * {@code OUT_OF_SERVICE}, not {@code DOWN}. Ticketing may be quiet at night, so a lag of zero also counts as fresh,
 * and it also needs the Debezium connector running.
 */
public class SourceHealthIndicator implements HealthIndicator {

    private final List<StreamListener> listeners;
    private final WarehouseHealthIndicator warehouse;
    private final ListenerPauseCoordinator pauses;
    private final SourceActivity activity;
    private final Duration freshness;
    private final Clock clock;
    private final @Nullable ConnectorStatus connector;
    private final BooleanSupplier caughtUp;

    /**
     * @param connector null for GTFS-realtime
     * @param caughtUp whether the consumer lag of the source is zero; ignored without a connector
     */
    public SourceHealthIndicator(
            List<StreamListener> listeners,
            WarehouseHealthIndicator warehouse,
            ListenerPauseCoordinator pauses,
            SourceActivity activity,
            Duration freshness,
            Clock clock,
            @Nullable ConnectorStatus connector,
            BooleanSupplier caughtUp) {
        this.listeners = List.copyOf(listeners);
        this.warehouse = warehouse;
        this.pauses = pauses;
        this.activity = activity;
        this.freshness = freshness;
        this.clock = clock;
        this.connector = connector;
        this.caughtUp = caughtUp;
    }

    public void bindTo(MeterRegistry meters, String source) {
        Gauge.builder("pti.source.health", () -> current().getStatus().equals(Status.UP) ? 1 : 0)
                .tag("source", source)
                .register(meters);
    }

    @Override
    public Health health() {
        return current();
    }

    private Health current() {
        Set<String> paused = new TreeSet<>();
        boolean running = true;
        for (StreamListener l : listeners) {
            running &= pauses.isRunning(l);
            pauses.reasons(l).forEach(r -> paused.add(r.tag()));
        }
        Optional<Instant> lastCommit = listeners.stream()
                .map(StreamListener::source)
                .map(activity::lastCommit)
                .flatMap(Optional::stream)
                .max(Instant::compareTo);
        boolean recent = lastCommit
                .map(t -> Duration.between(t, clock.instant()).compareTo(freshness) < 0)
                .orElse(false);

        Health.Builder builder;
        @Nullable String connectorState = connector == null ? null : connector.state();
        if (paused.contains(Reason.FLAG.tag())) {
            builder = Health.outOfService();
        } else if (!warehouse.isUp()
                || !running
                || paused.contains(Reason.BACKOFF.tag())
                || paused.contains(Reason.CIRCUIT.tag())
                || (connector != null && !"RUNNING".equals(connectorState))) {
            builder = Health.down();
        } else if (recent || (connector != null && caughtUp.getAsBoolean())) {
            builder = Health.up();
        } else {
            builder = Health.down();
        }
        lastCommit.ifPresent(t -> builder.withDetail("lastCommitAt", t.toString()));
        listeners.stream()
                .map(StreamListener::source)
                .map(activity::lastRecordTimestamp)
                .flatMap(Optional::stream)
                .max(Instant::compareTo)
                .ifPresent(t -> builder.withDetail("maxRecordTs", t.toString()));
        builder.withDetail("pausedReasons", paused);
        if (connectorState != null) {
            builder.withDetail("connectorState", connectorState);
        }
        return builder.build();
    }
}
