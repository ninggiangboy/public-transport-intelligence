package dev.pti.analytics.eta.domain;

import java.time.Duration;

/**
 * The tunable values of the historical ETA (DOC-23 §13, {@code pti.analytics.eta.*}). The arrival and real-time keys
 * are read by {@code api}; they are here so that the key table is bound in one place.
 *
 * @param window how far back samples are aggregated
 * @param mediumMin the sample count from which confidence is medium (§7.3)
 * @param highMin the sample count from which confidence is high
 * @param realtimeEnabled whether arrivals use real-time predictions (FR-06.3)
 * @param realtimeMaxAge the oldest real-time prediction arrivals use
 * @param arrivalsDefaultLimit how many arrivals an endpoint returns by default
 * @param arrivalsHorizon how far ahead arrivals are listed
 */
public record EtaSettings(
        Duration window,
        int mediumMin,
        int highMin,
        boolean realtimeEnabled,
        Duration realtimeMaxAge,
        int arrivalsDefaultLimit,
        Duration arrivalsHorizon) {}
