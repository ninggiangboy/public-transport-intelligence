package dev.pti.api.transit.domain;

import java.time.Duration;

/**
 * The {@code pti.analytics.eta.*} keys that {@code GET /stops/{id}/arrivals} reads (DOC-23 §13): the defaults of its
 * parameters, the realtime switch (F-ANL-06) and the confidence levels.
 */
public record ArrivalSettings(
        int defaultLimit,
        Duration defaultHorizon,
        boolean realtimeEnabled,
        Duration realtimeMaxAge,
        ConfidenceThresholds confidence) {

    /** The bounds of the {@code limit} parameter (DOC-32 E-08). */
    public static final int MIN_LIMIT = 1;

    public static final int MAX_LIMIT = 30;

    /** The bounds of the {@code horizon} parameter (DOC-32 E-08). */
    public static final Duration MIN_HORIZON = Duration.ofMinutes(15);

    public static final Duration MAX_HORIZON = Duration.ofHours(3);

    public ArrivalSettings {
        if (defaultLimit < MIN_LIMIT || defaultLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("default limit out of range: " + defaultLimit);
        }
        if (defaultHorizon.compareTo(MIN_HORIZON) < 0 || defaultHorizon.compareTo(MAX_HORIZON) > 0) {
            throw new IllegalArgumentException("default horizon out of range: " + defaultHorizon);
        }
    }
}
