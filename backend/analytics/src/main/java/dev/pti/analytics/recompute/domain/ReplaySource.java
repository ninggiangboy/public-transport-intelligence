package dev.pti.analytics.recompute.domain;

/**
 * The sources a raw zone replay reads (the values of {@code ops.etl_source}); the recompute decides from them which
 * detectors to run (DOC-23 §11.1). {@code etl} has its own enum with the same names, which it maps by name: the
 * library does not depend on {@code etl}.
 */
public enum ReplaySource {
    GTFS_RT_VEHICLE_POSITION,
    GTFS_RT_TRIP_UPDATE,
    TICKETING_SALES,
    TICKETING_SALE_POINTS,
    GTFS_STATIC
}
