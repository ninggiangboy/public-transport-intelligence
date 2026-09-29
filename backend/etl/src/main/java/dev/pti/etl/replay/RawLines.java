package dev.pti.etl.replay;

import dev.pti.common.error.DeserializationException;
import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.UnreadableRecordException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One line of a raw zone object, {@code {key, value, offset, timestamp, headers}} with a base64 value (DOC-09 §7),
 * back into the {@link InboundMessage} that {@code etl-stream} built from the same Kafka record.
 */
final class RawLines {

    private RawLines() {}

    /**
     * @throws UnreadableRecordException when the line is not a raw zone record; the dead letter carries the Kafka
     *     position when the line got that far
     */
    static InboundMessage parse(EtlSource source, String topic, int partition, String line) {
        JsonNode node;
        try {
            node = MessageJson.mapper().readTree(line);
        } catch (JacksonException e) {
            throw unreadable(source, null, null, null, line, "Raw zone line is not JSON: " + e.getOriginalMessage());
        }
        JsonNode offsetNode = node.path("offset");
        Long offset = offsetNode.isIntegralNumber() ? offsetNode.asLong() : null;
        Instant timestamp;
        try {
            timestamp = Instant.parse(node.path("timestamp").asString(""));
        } catch (DateTimeParseException e) {
            throw unreadable(
                    source,
                    offset == null ? null : topic,
                    partition,
                    offset,
                    line,
                    "Raw zone line has no valid timestamp");
        }
        if (offset == null) {
            throw unreadable(source, null, null, null, line, "Raw zone line has no offset");
        }
        JsonNode keyNode = node.path("key");
        String key = keyNode.isNull() || keyNode.isMissingNode() ? null : keyNode.asString();
        byte[] value;
        JsonNode valueNode = node.path("value");
        if (valueNode.isNull() || valueNode.isMissingNode()) {
            value = null;
        } else {
            try {
                value = Base64.getDecoder().decode(valueNode.asString());
            } catch (IllegalArgumentException e) {
                throw unreadable(source, topic, partition, offset, line, "Raw zone value is not base64");
            }
        }
        Map<String, String> headers = new HashMap<>();
        for (JsonNode header : node.path("headers")) {
            JsonNode headerValue = header.path("value");
            if (header.has("key") && !headerValue.isNull() && !headerValue.isMissingNode()) {
                headers.put(header.path("key").asString(), headerValue.asString());
            }
        }
        return new InboundMessage(source, key, value, topic, partition, offset, timestamp, headers);
    }

    private static UnreadableRecordException unreadable(
            EtlSource source, String topic, Integer partition, Long offset, String line, String reason) {
        InboundMessage message = new InboundMessage(
                source,
                null,
                line.getBytes(StandardCharsets.UTF_8),
                topic,
                topic == null ? null : partition,
                topic == null ? null : offset,
                null,
                Map.of());
        return new UnreadableRecordException(message, new DeserializationException(reason, null));
    }
}
