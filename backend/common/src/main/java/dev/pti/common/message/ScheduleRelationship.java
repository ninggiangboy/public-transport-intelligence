package dev.pti.common.message;

/** GTFS-realtime {@code StopTimeUpdate.ScheduleRelationship}, as far as the simulator uses it. */
public enum ScheduleRelationship {
    SCHEDULED,
    SKIPPED,
    NO_DATA
}
