package dev.pti.analytics.eta.application;

import java.time.Instant;

/**
 * @param hour the run's hour {@code H}
 * @param force run even when the source data has not changed: a replay or an operator asked for it (DOC-23 §7.2)
 */
public record EtaPlanRequest(Instant hour, boolean force) {}
