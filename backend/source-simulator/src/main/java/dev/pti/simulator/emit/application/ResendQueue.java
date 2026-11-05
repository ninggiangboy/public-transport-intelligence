package dev.pti.simulator.emit.application;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.emit.application.port.MessageRewriter;
import dev.pti.simulator.emit.application.port.MessageSink;
import dev.pti.simulator.emit.application.port.ResendMetrics;
import dev.pti.simulator.emit.domain.OutboundMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Messages the {@code duplicates} scenario sends again later (DOC-25 §7.5). A resend is the original message with a
 * new {@code message_id} and {@code produced_at}; the ledger row points back at the original. Items are due on real
 * time and go straight to the Kafka sink, past the interceptors. Thread-safe: filled by the thread that sends, drained
 * by {@code sim-resend}.
 */
public final class ResendQueue {

    private final MessageSink sink;
    private final BusinessClock clock;
    private final int capacity;
    private final ResendMetrics metrics;
    private final MessageRewriter rewriter;
    private final PriorityQueue<Item> queue =
            new PriorityQueue<>(Comparator.comparingLong(Item::dueMillis).thenComparingLong(Item::order));
    private long order;

    public ResendQueue(
            MessageSink sink, BusinessClock clock, int capacity, ResendMetrics metrics, MessageRewriter rewriter) {
        this.sink = sink;
        this.clock = clock;
        this.capacity = capacity;
        this.metrics = metrics;
        this.rewriter = rewriter;
    }

    /**
     * Queues a resend of {@code original} at real time {@code dueMillis} for scenario run {@code runId}.
     *
     * @return {@code false} when the queue is full and the resend is dropped (it never reaches the ledger)
     */
    public synchronized boolean add(OutboundMessage original, long dueMillis, UUID runId) {
        if (queue.size() >= capacity) {
            metrics.skipped();
            return false;
        }
        queue.add(new Item(dueMillis, order++, original, runId));
        metrics.depth(queue.size());
        return true;
    }

    public synchronized int depth() {
        return queue.size();
    }

    /** Sends every resend due by now. Runs on {@code sim-resend}. */
    public void tick() {
        drainUntil(clock.realNow().toEpochMilli());
    }

    /** Sends every resend due by real time {@code nowMillis}. Visible for tests driving a manual clock. */
    public void drainUntil(long nowMillis) {
        for (Item item : due(nowMillis)) {
            sink.send(resend(item));
        }
    }

    private synchronized List<Item> due(long nowMillis) {
        List<Item> due = new ArrayList<>();
        while (!queue.isEmpty() && queue.peek().dueMillis() <= nowMillis) {
            due.add(queue.poll());
        }
        if (!due.isEmpty()) {
            metrics.depth(queue.size());
        }
        return due;
    }

    private OutboundMessage resend(Item item) {
        OutboundMessage original = item.message();
        UUID messageId = UuidCreator.getTimeOrderedEpoch();
        Instant producedAt = clock.realNow();
        return new OutboundMessage(
                original.topic(),
                original.key(),
                rewriter.rewrite(original.value(), messageId, producedAt),
                original.headers(),
                original.ledger().resend(messageId, producedAt, item.runId()));
    }

    private record Item(long dueMillis, long order, OutboundMessage message, UUID runId) {}
}
