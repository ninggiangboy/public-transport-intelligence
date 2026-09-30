package dev.pti.api.transit.domain;

/** How the delays of a route are grouped (DOC-32 E-03). */
public enum BucketSize {
    /** One bucket per hour of the scheduled arrival. */
    HOUR,
    /** One bucket per local service date. */
    DAY,
    /** One bucket per ISO day of the week and local hour, across the whole range. */
    HOUR_OF_WEEK
}
