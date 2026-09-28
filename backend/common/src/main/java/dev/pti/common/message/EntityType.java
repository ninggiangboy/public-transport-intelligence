package dev.pti.common.message;

/** The kind of GTFS-realtime entity an envelope carries (DOC-09 §2). */
public enum EntityType {
    VEHICLE_POSITION,
    TRIP_UPDATE
}
