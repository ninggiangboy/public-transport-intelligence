package dev.pti.simulator.emit.adapter.out.json;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.Envelope;
import dev.pti.simulator.emit.domain.EnvelopeSerializer;

/** {@link EnvelopeSerializer} on the shared message JSON (DOC-09). */
public final class JacksonEnvelopeSerializer implements EnvelopeSerializer {

    @Override
    public String serialize(Envelope<?> envelope) {
        return MessageJson.mapper().writeValueAsString(envelope);
    }
}
