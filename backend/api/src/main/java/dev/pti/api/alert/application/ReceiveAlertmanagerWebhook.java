package dev.pti.api.alert.application;

import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.alert.application.port.WebhookMetrics;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertmanagerAlert;
import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /internal/alerts/alertmanager} (DOC-32 E-80): the notices of Alertmanager become rows of the unified
 * alert feed (ADR-0023). A firing alert is inserted unless an alert with its dedup key exists, so Alertmanager sending
 * it again is a no-op; a resolved one sets {@code resolved_at} of the row with its dedup key. The whole request is one
 * transaction. After it has returned, each inserted row is announced as {@code alert.created} and each resolved one as
 * {@code alert.updated}, always for the audience {@code ENGINEERING}, and each notice is counted by its outcome.
 */
public final class ReceiveAlertmanagerWebhook {

    private static final Logger log = LoggerFactory.getLogger(ReceiveAlertmanagerWebhook.class);

    private final AlertStore store;
    private final UiEventPublisher events;
    private final WebhookMetrics metrics;
    private final BusinessClock clock;
    private final TransactionRunner tx;

    public ReceiveAlertmanagerWebhook(
            AlertStore store,
            UiEventPublisher events,
            WebhookMetrics metrics,
            BusinessClock clock,
            TransactionRunner tx) {
        this.store = store;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = tx;
    }

    public void execute(List<AlertmanagerAlert> notices) {
        Processed processed = tx.inTransaction(() -> apply(notices));
        processed.outcomes.forEach(metrics::outcome);
        if (!notices.isEmpty()) {
            log.info(
                    "alertmanager webhook processed notices={} created={} resolved={}",
                    notices.size(),
                    Collections.frequency(processed.outcomes, WebhookMetrics.CREATED),
                    Collections.frequency(processed.outcomes, WebhookMetrics.RESOLVED));
        }
        // In the order of the notices, so that a firing notice is announced before its own resolution.
        events.publishAll(processed.announcements.stream()
                .map(announcement -> announcement.event(clock))
                .toList());
    }

    private Processed apply(List<AlertmanagerAlert> notices) {
        Processed processed = new Processed();
        for (AlertmanagerAlert notice : notices) {
            if (notice.status() == AlertmanagerAlert.Status.FIRING) {
                processed.add(
                        store.insertIfAbsent(notice.toNewAlert()),
                        WebhookMetrics.CREATED,
                        WebhookMetrics.DUPLICATE,
                        true);
            } else {
                processed.add(
                        store.resolve(notice.dedupKey()),
                        WebhookMetrics.RESOLVED,
                        WebhookMetrics.UNKNOWN_RESOLVED,
                        false);
            }
        }
        return processed;
    }

    /** One row to announce after the commit. */
    private record Announcement(Alert alert, boolean created) {

        UiEvent event(BusinessClock clock) {
            return created ? AlertEvents.created(alert, clock.realNow()) : AlertEvents.updated(alert, clock.realNow());
        }
    }

    /** What the transaction did, to be told to the metrics and the screens once it has committed. */
    private static final class Processed {

        private final List<String> outcomes = new ArrayList<>();
        private final List<Announcement> announcements = new ArrayList<>();

        void add(Optional<Alert> row, String done, String nothing, boolean insert) {
            if (row.isEmpty()) {
                outcomes.add(nothing);
                return;
            }
            outcomes.add(done);
            announcements.add(new Announcement(row.get(), insert));
        }
    }
}
