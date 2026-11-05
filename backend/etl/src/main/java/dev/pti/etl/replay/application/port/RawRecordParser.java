package dev.pti.etl.replay.application.port;

import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.core.domain.InboundMessage;

/**
 * Parses one line of a raw zone object, {@code {key, value, offset, timestamp, headers}} with a base64 value
 * (DOC-09 §7), back into the {@link InboundMessage} that {@code etl-stream} built from the same Kafka record.
 */
public interface RawRecordParser {

    InboundMessage parse(EtlSource source, String topic, int partition, String line);
}
