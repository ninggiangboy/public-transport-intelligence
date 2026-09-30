package dev.pti.analytics.disruption.domain;

import java.time.Instant;

/**
 * An observed arrival of a vehicle at a stop (DOC-23 §2.1): a {@code fact_trip_update} row with
 * {@code is_observed}, {@code SCHEDULED} and a delay.
 *
 * @param observedAt {@code coalesce(arrival_time, departure_time)}
 * @param delaySeconds negative when the vehicle was early
 */
public record Arrival(int directionId, String stopId, Instant observedAt, int delaySeconds) {}
