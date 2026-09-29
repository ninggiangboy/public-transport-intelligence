package dev.pti.simulator.emit;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.ledger.Ledger;
import dev.pti.simulator.ledger.LedgerEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Sends through {@link KafkaTemplate} (DOC-25 §6.4). The ledger row is written from the success callback only; a
 * failed send is counted and logged (at most one line every 10 s) and leaves no ledger row. When the producer blocks
 * for {@code max.block.ms}, the emission is skipped and sends fail fast for a second, so a Kafka outage does not stall
 * the emission loop for a second per message.
 */
public final class KafkaMessageSink implements MessageSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSink.class);

    private static final long FAIL_FAST_MILLIS = 1_000;
    private static final long WARN_EVERY_MILLIS = 10_000;

    private final KafkaTemplate<String, String> template;
    private final Ledger ledger;
    private final BusinessClock clock;
    private final MeterRegistry registry;
    private final Throughput throughput;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE);
    private volatile long failFastUntil = Long.MIN_VALUE;

    public KafkaMessageSink(
            KafkaTemplate<String, String> template,
            Ledger ledger,
            BusinessClock clock,
            MeterRegistry registry,
            Throughput throughput) {
        this.template = template;
        this.ledger = ledger;
        this.clock = clock;
        this.registry = registry;
        this.throughput = throughput;
    }

    @Override
    public boolean send(OutboundMessage message) {
        long now = clock.realNow().toEpochMilli();
        if (now < failFastUntil) {
            skipped("send_timeout").increment();
            return false;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(), message.value());
        message.headers()
                .forEach((name, value) ->
                        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
        try {
            template.send(record).whenComplete((result, error) -> {
                if (error == null) {
                    acknowledged(message, result);
                } else {
                    failed(message, error);
                }
            });
            return true;
        } catch (RuntimeException e) {
            failFastUntil = now + FAIL_FAST_MILLIS;
            skipped(hasCause(e, BufferExhaustedException.class) ? "backpressure" : "send_timeout")
                    .increment();
            warn("Kafka send blocked, skipping emissions for {} ms: {}", FAIL_FAST_MILLIS, e.toString());
            return false;
        }
    }

    private void acknowledged(OutboundMessage message, SendResult<String, String> result) {
        ledger.record(
                message.ledger(),
                message.topic(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        throughput.record(message.topic());
        counter(
                        "pti.sim.messages.sent",
                        "topic",
                        message.topic(),
                        "entity_type",
                        message.ledger().entityType(),
                        "kind",
                        kind(message.ledger()))
                .increment();
    }

    private static String kind(LedgerEntry entry) {
        if (entry.invalidKind() != null) {
            return "invalid";
        }
        return entry.resendOf() != null ? "resend" : "valid";
    }

    private void failed(OutboundMessage message, Throwable error) {
        counter("pti.sim.send.errors", "topic", message.topic()).increment();
        counter(
                        "pti.errors",
                        "kind",
                        "transient_infra",
                        "type",
                        error.getClass().getSimpleName())
                .increment();
        warn("Kafka send failed on {}: {}", message.topic(), error.toString());
    }

    private Counter skipped(String reason) {
        return counter("pti.sim.emissions.skipped", "reason", reason);
    }

    private Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(
                name + String.join("|", tags),
                k -> Counter.builder(name).tags(tags).register(registry));
    }

    private void warn(String format, Object... args) {
        long now = clock.realNow().toEpochMilli();
        long last = lastWarning.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_EVERY_MILLIS) && lastWarning.compareAndSet(last, now)) {
            log.warn(format, args);
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** Waits until every record handed to the producer is acknowledged or failed (DOC-25 §10). */
    public void flush() {
        template.flush();
    }
}
