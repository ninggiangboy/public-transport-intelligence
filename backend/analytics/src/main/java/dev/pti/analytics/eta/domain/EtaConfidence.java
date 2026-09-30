package dev.pti.analytics.eta.domain;

/**
 * How much a historical ETA can be trusted (DOC-23 §7.3), from the number of samples behind it. It is not stored:
 * whoever reads {@code insight_eta_prediction} derives it with the same thresholds.
 */
public enum EtaConfidence {
    /** No row: the arrival keeps its scheduled time. */
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    /**
     * @param sampleCount the row's {@code sample_count}; zero or less means there is no row
     */
    public static EtaConfidence of(int sampleCount, EtaSettings settings) {
        if (sampleCount <= 0) {
            return NONE;
        }
        if (sampleCount < settings.mediumMin()) {
            return LOW;
        }
        return sampleCount < settings.highMin() ? MEDIUM : HIGH;
    }
}
