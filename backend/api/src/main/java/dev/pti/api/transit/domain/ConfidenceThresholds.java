package dev.pti.api.transit.domain;

/** {@code pti.analytics.eta.confidence.medium-min} and {@code .high-min} (DOC-23 §7.3). */
public record ConfidenceThresholds(int mediumMin, int highMin) {

    public ConfidenceThresholds {
        if (mediumMin < 1 || highMin <= mediumMin) {
            throw new IllegalArgumentException("Need 1 <= mediumMin < highMin: " + mediumMin + ", " + highMin);
        }
    }
}
