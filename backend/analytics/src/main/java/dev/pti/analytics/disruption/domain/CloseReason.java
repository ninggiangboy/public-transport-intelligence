package dev.pti.analytics.disruption.domain;

/** Why a disruption episode closed ({@code insight_service_disruption.close_reason}, DOC-23 §6.3). */
public enum CloseReason {
    /** The delay fell back: {@code close-consecutive} buckets in a row with a z-score below {@code close-z}. */
    RECOVERED,
    /** The route stopped sending arrivals and the bucket that closed the episode had too few samples. */
    NO_DATA,
    /** The episode lasted {@code max-episode-duration}; the new level of delay is taken as normal. */
    MAX_DURATION
}
