package dev.pti.api.transit.domain;

import org.jspecify.annotations.Nullable;

/**
 * The historical delay at one stop of a direction (DOC-32 E-04). A stop without history has {@code sampleCount} 0,
 * confidence {@code NONE} and no delay figures.
 */
public record DelayProfileStop(
        String stopId,
        String name,
        int stopSequence,
        @Nullable EtaRow eta,
        Confidence confidence) {

    public int sampleCount() {
        return eta != null ? eta.sampleCount() : 0;
    }
}
