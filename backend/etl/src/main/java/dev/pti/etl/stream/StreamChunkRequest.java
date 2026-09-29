package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One poll, processed as one chunk (DOC-19 §6.1).
 *
 * @param batchId UUIDv7 generated before the transaction (DR-63); a retry after an infrastructure error gets a new one
 * @param replay always false for live listeners
 */
public record StreamChunkRequest(
        UUID batchId,
        EtlSource source,
        String listenerId,
        String consumerGroup,
        String instanceId,
        List<InboundMessage> messages,
        boolean replay) {

    public StreamChunkRequest {
        messages = List.copyOf(messages);
    }

    /** {@code {"<topic>-<partition>": [first, last]}}, the {@code offsets} column of {@code etl_stream_batch}. */
    public Map<String, long[]> offsets() {
        Map<String, long[]> offsets = new TreeMap<>();
        for (InboundMessage m : messages) {
            String topic = m.topic();
            Long boxed = m.offset();
            if (topic == null || boxed == null) {
                continue;
            }
            long offset = boxed;
            offsets.merge(topic + "-" + m.partition(), new long[] {offset, offset}, (a, b) ->
                    new long[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        return offsets;
    }

    /** The oldest Kafka record timestamp of the poll (latency, DR-57). */
    public Optional<Instant> minRecordTimestamp() {
        return messages.stream()
                .map(InboundMessage::recordTimestamp)
                .filter(t -> t != null)
                .min(Instant::compareTo);
    }
}
