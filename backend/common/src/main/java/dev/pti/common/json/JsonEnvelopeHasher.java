package dev.pti.common.json;

import dev.pti.common.message.Envelope;
import dev.pti.common.message.EnvelopeHasher;

/** {@link EnvelopeHasher} on the canonical JSON of {@link PayloadHasher}. */
public final class JsonEnvelopeHasher implements EnvelopeHasher {

    @Override
    public String hash(Envelope<?> envelope) {
        return PayloadHasher.hash(envelope);
    }
}
