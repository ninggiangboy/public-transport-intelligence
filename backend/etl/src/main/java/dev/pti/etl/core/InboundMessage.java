package dev.pti.etl.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One inbound record, whatever the transport: a Kafka record, a raw-zone line or a dead letter being replayed
 * (DOC-19 §4.1). {@code value} holds the bytes exactly as received and may be malformed; {@code null} is a
 * tombstone.
 *
 * @param recordTimestamp the Kafka CreateTime (real time), the mark of the raw zone and of latency (DR-57)
 */
public record InboundMessage(
        EtlSource source,
        @Nullable String key,
        byte @Nullable [] value,
        @Nullable String topic,
        @Nullable Integer partition,
        @Nullable Long offset,
        @Nullable Instant recordTimestamp,
        Map<String, String> headers) {

    public InboundMessage {
        headers = Map.copyOf(headers);
    }

    public boolean isTombstone() {
        return value == null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InboundMessage m
                && source == m.source
                && Objects.equals(key, m.key)
                && Arrays.equals(value, m.value)
                && Objects.equals(topic, m.topic)
                && Objects.equals(partition, m.partition)
                && Objects.equals(offset, m.offset)
                && Objects.equals(recordTimestamp, m.recordTimestamp)
                && headers.equals(m.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, key, Arrays.hashCode(value), topic, partition, offset);
    }

    @Override
    public String toString() {
        return "InboundMessage[" + source + " " + topic + "-" + partition + "@" + offset + "]";
    }
}
