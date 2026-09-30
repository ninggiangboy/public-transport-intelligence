package dev.pti.api.transit.domain;

/** How much history stands behind a predicted arrival (DOC-23 §7.3). Derived when read, never stored. */
public enum Confidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    /**
     * @param sampleCount the number of observations behind the prediction, 0 when there is none
     */
    public static Confidence of(int sampleCount, ConfidenceThresholds thresholds) {
        if (sampleCount <= 0) {
            return NONE;
        }
        if (sampleCount < thresholds.mediumMin()) {
            return LOW;
        }
        return sampleCount < thresholds.highMin() ? MEDIUM : HIGH;
    }
}
