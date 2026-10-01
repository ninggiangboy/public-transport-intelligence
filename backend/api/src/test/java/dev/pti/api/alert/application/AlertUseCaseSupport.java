package dev.pti.api.alert.application;

import dev.pti.apitest.InMemoryAlerts;
import dev.pti.apitest.RecordingUiEvents;
import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The parts the use case tests of the alert feature share: the in-memory store, a clock, a transaction runner and a
 * publisher that both write to one {@link #journal}, so that a test can check that an event is published after the
 * transaction has returned (DOC-49 §5.2).
 */
abstract class AlertUseCaseSupport {

    static final Instant REAL_NOW = Instant.parse("2026-09-29T21:14:02.500Z");

    final InMemoryAlerts alerts = new InMemoryAlerts();
    final RecordingUiEvents recorded = new RecordingUiEvents();
    final List<String> journal = new ArrayList<>();
    final BusinessClock clock = new BusinessClock(Clock.fixed(REAL_NOW, ZoneOffset.UTC), Duration.ofHours(-6));

    final TransactionRunner tx = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            journal.add("begin");
            T result = work.get();
            journal.add("commit");
            return result;
        }

        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            return inTransaction(work);
        }
    };

    final UiEventPublisher events = new UiEventPublisher() {
        @Override
        public void publish(UiEvent event) {
            journal.add("publish " + event.type());
            recorded.publish(event);
        }

        @Override
        public void publishAll(List<UiEvent> batch) {
            batch.forEach(this::publish);
        }
    };
}
