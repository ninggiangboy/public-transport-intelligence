package dev.pti.analytics.recompute.domain;

import dev.pti.analytics.core.domain.Detector;
import java.time.Instant;

/**
 * One unit of a recompute (DOC-23 §11.1): a route for bunching and disruption, a service date for OTP, the forced run
 * of an hour for ETA, a 15-minute window for ticketing. It runs in one transaction under one advisory lock.
 *
 * @param scope the route id, the ISO date or the ISO hour, as the log and the metrics show it
 * @param from start of the range of event time the item recomputes
 * @param to end of that range
 */
public record WorkItem(Detector detector, String scope, Instant from, Instant to) {}
