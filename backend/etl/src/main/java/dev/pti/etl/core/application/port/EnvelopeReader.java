package dev.pti.etl.core.application.port;

import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.Payload;
import dev.pti.etl.core.domain.InboundMessage;

/**
 * Reads the bytes of a GTFS-realtime record into a validated envelope and its payload hash (DOC-20 §4.1). The
 * implementation lives in an adapter; the processor only sees the typed envelope, never JSON.
 */
public interface EnvelopeReader {

    /** A message that passed DQ-01, with the payload hash of DOC-09 §9. */
    record Read(Envelope<? extends Payload> envelope, String hash) {}

    Read read(InboundMessage message, EntityType expected);
}
