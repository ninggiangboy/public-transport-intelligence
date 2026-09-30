package dev.pti.analytics.bunching.domain;

/** {@code current_status} of a GTFS-realtime vehicle position (DOC-23 §5.2). */
public enum StopStatus {
    /** The vehicle is about to arrive at the stop. */
    INCOMING_AT,
    /** The vehicle is standing at the stop. */
    STOPPED_AT,
    /** The vehicle left the previous stop and is on its way to the stop. */
    IN_TRANSIT_TO
}
