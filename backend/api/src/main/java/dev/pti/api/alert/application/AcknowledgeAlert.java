package dev.pti.api.alert.application;

import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /alerts/{id}/ack} (DOC-32 E-21): an operator takes responsibility for an alert. Idempotent: an alert
 * that was acknowledged already is returned as it is, keeping the first operator, and nothing is written or
 * published. An alert that is resolved can still be acknowledged. The {@code alert.updated} event goes out after the
 * transaction has returned, and only when this call wrote the acknowledgement (DOC-49 §5.2).
 */
public final class AcknowledgeAlert {

    private static final Logger log = LoggerFactory.getLogger(AcknowledgeAlert.class);

    private static final String OPERATION = "ack";

    private final AlertStore store;
    private final UiEventPublisher events;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final TransactionRunner tx;

    public AcknowledgeAlert(
            AlertStore store,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock,
            TransactionRunner tx) {
        this.store = store;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * @param actor {@code user:<username>} of the operator
     * @return the alert as it is after the call
     * @throws NotFoundException when there is no such alert
     */
    public Alert execute(String actor, UUID id) {
        Result result = tx.inTransaction(() -> acknowledge(actor, id));
        if (!result.written) {
            metrics.recorded(OPERATION, WriteMetrics.IDEMPOTENT);
            return result.alert;
        }
        metrics.recorded(OPERATION, WriteMetrics.CREATED);
        log.info("alert acknowledged alertId={} actor={}", id, actor);
        events.publish(AlertEvents.updated(result.alert, clock.realNow()));
        return result.alert;
    }

    private Result acknowledge(String actor, UUID id) {
        Optional<Alert> updated = store.acknowledge(id, actor);
        if (updated.isPresent()) {
            return new Result(updated.get(), true);
        }
        Alert current = store.find(id).orElseThrow(() -> new NotFoundException("The alert does not exist."));
        return new Result(current, false);
    }

    private record Result(Alert alert, boolean written) {}
}
