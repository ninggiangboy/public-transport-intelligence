package dev.pti.simulator.emit.domain;

import dev.pti.common.message.Envelope;

/**
 * Serializes an envelope to the bytes the simulator sends (DOC-09), as a port so that {@code domain} stays free of
 * Jackson. The implementation, {@code emit.adapter.out.json.JacksonEnvelopeSerializer}, uses the shared message JSON.
 */
public interface EnvelopeSerializer {

    /** The wire form of {@code envelope}. */
    String serialize(Envelope<?> envelope);
}
